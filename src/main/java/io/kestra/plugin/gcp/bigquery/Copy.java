package io.kestra.plugin.gcp.bigquery;

import com.google.cloud.bigquery.*;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.executions.metrics.Timer;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jakarta.validation.constraints.NotNull;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: gcp_bq_copy
                namespace: company.team

                tasks:
                  - id: copy
                    type: io.kestra.plugin.gcp.bigquery.Copy
                    operationType: COPY
                    sourceTables:
                      - "my_project.my_dataset.my_table$20130908"
                    destinationTable: "my_project.my_dataset.my_table"
                """
        )
    },
    metrics = {
        @Metric(name = "num.child.jobs", type = Counter.TYPE, description = "The number of child jobs executed."),
        @Metric(name = "duration", type = Timer.TYPE, description= "The time it took for the job to run.")
    }
)
@Schema(
    title = "Copy a BigQuery table or partition to another one."
)
public class Copy extends AbstractJob implements RunnableTask<Copy.Output> {
    @Schema(
        title = "The source tables.",
        description = "Can be table or partitions."
    )
    @NotNull
    private Property<List<String>> sourceTables;

    @Schema(
        title = "The destination table.",
        description = "If not provided a new table is created."
    )
    @NotNull
    private Property<String> destinationTable;

    @Schema(
        title = "Sets the supported operation types in table copy job.",
        description =
            "* `COPY`: The source and destination table have the same table type.\n" +
            "* `SNAPSHOT`: The source table type is TABLE and the destination table type is SNAPSHOT.\n" +
            "* `RESTORE`: The source table type is SNAPSHOT and the destination table type is TABLE.\n" +
            "* `CLONE`: The source and destination table have the same table type, but only bill for unique data."
    )
    private Property<OperationType> operationType;

    @Override
    public Copy.Output run(RunContext runContext) throws Exception {
        BigQuery connection = this.connection(runContext);
        Logger logger = runContext.logger();

        CopyJobConfiguration jobConfiguration = this.jobConfiguration(runContext);

        logger.debug("Starting copy from {} to {}", jobConfiguration.getSourceTables(), jobConfiguration.getDestinationTable());

        Job copyJob = this.waitForJob(
            logger,
            () -> connection
                .create(JobInfo.newBuilder(jobConfiguration)
                    .setJobId(BigQueryService.jobId(runContext, this))
                    .build()
                ),
            runContext.render(this.dryRun).as(Boolean.class).orElseThrow(),
            runContext);

        JobStatistics.CopyStatistics copyJobStatistics = copyJob.getStatistics();

        this.metrics(runContext, copyJobStatistics, copyJob);

        Output.OutputBuilder output = Output.builder()
            .jobId(copyJob.getJobId().getJob());

        return output.build();
    }

    protected CopyJobConfiguration jobConfiguration(RunContext runContext) throws IllegalVariableEvaluationException {
        CopyJobConfiguration.Builder builder = CopyJobConfiguration.newBuilder(
            BigQueryService.tableId(runContext.render(this.destinationTable).as(String.class).orElseThrow()),
            runContext.render(this.sourceTables).asList(String.class).stream().map(BigQueryService::tableId).collect(Collectors.toList())
        );

        if (this.writeDisposition != null) {
            builder.setWriteDisposition(runContext.render(this.writeDisposition).as(JobInfo.WriteDisposition.class).orElseThrow());
        }

        if (this.createDisposition != null) {
            builder.setCreateDisposition(runContext.render(this.createDisposition).as(JobInfo.CreateDisposition.class).orElseThrow());
        }

        if (this.operationType != null) {
            builder.setOperationType(runContext.render(this.operationType).as(OperationType.class).orElseThrow().name());
        }

        if (this.jobTimeout != null) {
            builder.setJobTimeoutMs(runContext.render(this.jobTimeout).as(Duration.class).orElseThrow().toMillis());
        }

        Map<String, String> finalLabels = new HashMap<>(BigQueryService.labels(runContext));
        var renderedLabels = runContext.render(this.labels).asMap(String.class, String.class);
        if (!renderedLabels.isEmpty()) {
            finalLabels.putAll(renderedLabels);
        }
        builder.setLabels(finalLabels);

        return builder.build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The job id"
        )
        private String jobId;
    }

    private String[] tags(JobStatistics.CopyStatistics stats, Job queryJob) {
        return new String[]{
            "project_id", queryJob.getJobId().getProject(),
            "location", queryJob.getJobId().getLocation(),
        };
    }

    private void metrics(RunContext runContext, JobStatistics.CopyStatistics stats, Job queryJob) {
        String[] tags = this.tags(stats, queryJob);

        if (stats.getNumChildJobs() != null) {
            runContext.metric(Counter.of("num.child.jobs", stats.getNumChildJobs(), tags));
        }

        runContext.metric(Timer.of("duration", Duration.ofMillis(stats.getEndTime() - stats.getStartTime()), tags));
    }

    public enum OperationType {
        COPY,
        SNAPSHOT,
        RESTORE,
        CLONE,
    }
}