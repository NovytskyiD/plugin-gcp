package org.kestra.task.gcp.bigquery;

import com.devskiller.friendly_id.FriendlyId;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.LegacySQLTypeName;
import com.google.cloud.bigquery.Schema;
import com.google.common.collect.ImmutableMap;
import io.micronaut.context.annotation.Value;
import io.micronaut.test.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.kestra.core.runners.RunContext;
import org.kestra.core.runners.RunContextFactory;
import org.kestra.core.utils.TestsUtils;

import java.util.Collections;
import javax.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@MicronautTest
class LoadFromGcsTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Value("${kestra.tasks.bigquery.project}")
    private String project;

    @Value("${kestra.tasks.bigquery.dataset}")
    private String dataset;

    @Test
    void fromJson() throws Exception {
        LoadFromGcs task = LoadFromGcs.builder()
            .id(LoadFromGcsTest.class.getSimpleName())
            .type(LoadFromGcs.class.getName())
            .from(Collections.singletonList(
                "gs://cloud-samples-data/bigquery/us-states/us-states.json"
            ))
            .destinationTable(project + "." + dataset + "." + FriendlyId.createFriendlyId())
            .format(AbstractLoad.Format.JSON)
            .schema(Schema.of(
                Field.of("name", LegacySQLTypeName.STRING),
                Field.of("post_abbr", LegacySQLTypeName.STRING)
            ))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, ImmutableMap.of());

        AbstractLoad.Output run = task.run(runContext);
        assertThat(run.getRows(), is(50L));
    }
}
