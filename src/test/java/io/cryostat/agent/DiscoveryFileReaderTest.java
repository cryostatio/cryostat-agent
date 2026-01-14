/*
 * Copyright The Cryostat Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cryostat.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import io.cryostat.agent.model.DiscoveryMetadata;
import io.cryostat.agent.model.DiscoveryNode;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class DiscoveryFileReaderTest {

    @TempDir Path tempDir;

    private DiscoveryFileReader reader;
    private ObjectMapper mapper;
    private Path mountPath;

    @BeforeEach
    public void setup() {
        mapper = new ObjectMapper();
        mountPath = tempDir.resolve("discovery");
        reader = new DiscoveryFileReader(mountPath.toString(), "metadata.json", "hierarchy.json");
    }

    @Test
    public void testReadMetadata_Success() throws IOException {
        // Create mount directory
        Files.createDirectories(mountPath);

        // Write metadata.json
        String metadataJson =
                "{\n"
                        + "  \"labels\": {\n"
                        + "    \"app\": \"my-app\",\n"
                        + "    \"environment\": \"production\"\n"
                        + "  },\n"
                        + "  \"annotations\": {\n"
                        + "    \"description\": \"My application\",\n"
                        + "    \"version\": \"1.0.0\"\n"
                        + "  }\n"
                        + "}";
        Files.writeString(mountPath.resolve("metadata.json"), metadataJson);

        // Read and verify
        Optional<DiscoveryMetadata> result = reader.readMetadata();

        MatcherAssert.assertThat(result.isPresent(), Matchers.is(true));
        DiscoveryMetadata metadata = result.get();
        MatcherAssert.assertThat(metadata.getLabels().size(), Matchers.equalTo(2));
        MatcherAssert.assertThat(metadata.getLabels().get("app"), Matchers.equalTo("my-app"));
        MatcherAssert.assertThat(
                metadata.getLabels().get("environment"), Matchers.equalTo("production"));
        MatcherAssert.assertThat(metadata.getAnnotations().size(), Matchers.equalTo(2));
        MatcherAssert.assertThat(
                metadata.getAnnotations().get("description"), Matchers.equalTo("My application"));
        MatcherAssert.assertThat(
                metadata.getAnnotations().get("version"), Matchers.equalTo("1.0.0"));
    }

    @Test
    public void testReadMetadata_FileNotFound() {
        // Don't create the file
        Optional<DiscoveryMetadata> result = reader.readMetadata();

        MatcherAssert.assertThat(result.isEmpty(), Matchers.is(true));
    }

    @Test
    public void testReadMetadata_EmptyMaps() throws IOException {
        Files.createDirectories(mountPath);
        String metadataJson = "{\n" + "  \"labels\": {},\n" + "  \"annotations\": {}\n" + "}";
        Files.writeString(mountPath.resolve("metadata.json"), metadataJson);

        Optional<DiscoveryMetadata> result = reader.readMetadata();

        MatcherAssert.assertThat(result.isPresent(), Matchers.is(true));
        DiscoveryMetadata metadata = result.get();
        MatcherAssert.assertThat(metadata.getLabels().isEmpty(), Matchers.is(true));
        MatcherAssert.assertThat(metadata.getAnnotations().isEmpty(), Matchers.is(true));
    }

    @Test
    public void testReadMetadata_MalformedJson() throws IOException {
        Files.createDirectories(mountPath);
        Files.writeString(mountPath.resolve("metadata.json"), "{ invalid json }");

        Optional<DiscoveryMetadata> result = reader.readMetadata();

        MatcherAssert.assertThat(result.isEmpty(), Matchers.is(true));
    }

    @Test
    public void testReadHierarchy_Success() throws IOException {
        Files.createDirectories(mountPath);

        // Create a hierarchy: Namespace -> Deployment -> Pod
        String hierarchyJson =
                "{\n"
                        + "  \"name\": \"my-namespace\",\n"
                        + "  \"nodeType\": \"Namespace\",\n"
                        + "  \"labels\": {\"kubernetes.io/metadata.name\": \"my-namespace\"},\n"
                        + "  \"children\": [\n"
                        + "    {\n"
                        + "      \"name\": \"my-deployment\",\n"
                        + "      \"nodeType\": \"Deployment\",\n"
                        + "      \"labels\": {\"app\": \"my-app\"},\n"
                        + "      \"children\": [\n"
                        + "        {\n"
                        + "          \"name\": \"my-pod-abc123\",\n"
                        + "          \"nodeType\": \"Pod\",\n"
                        + "          \"labels\": {\"pod-template-hash\": \"abc123\"},\n"
                        + "          \"children\": []\n"
                        + "        }\n"
                        + "      ]\n"
                        + "    }\n"
                        + "  ]\n"
                        + "}";
        Files.writeString(mountPath.resolve("hierarchy.json"), hierarchyJson);

        Optional<DiscoveryNode> result = reader.readHierarchy();

        MatcherAssert.assertThat(result.isPresent(), Matchers.is(true));
        DiscoveryNode namespace = result.get();
        MatcherAssert.assertThat(namespace.getName(), Matchers.equalTo("my-namespace"));
        MatcherAssert.assertThat(namespace.getNodeType(), Matchers.equalTo("Namespace"));
        MatcherAssert.assertThat(namespace.getChildren().size(), Matchers.equalTo(1));

        DiscoveryNode deployment = namespace.getChildren().get(0);
        MatcherAssert.assertThat(deployment.getName(), Matchers.equalTo("my-deployment"));
        MatcherAssert.assertThat(deployment.getNodeType(), Matchers.equalTo("Deployment"));
        MatcherAssert.assertThat(deployment.getChildren().size(), Matchers.equalTo(1));

        DiscoveryNode pod = deployment.getChildren().get(0);
        MatcherAssert.assertThat(pod.getName(), Matchers.equalTo("my-pod-abc123"));
        MatcherAssert.assertThat(pod.getNodeType(), Matchers.equalTo("Pod"));
        MatcherAssert.assertThat(pod.getChildren().isEmpty(), Matchers.is(true));
    }

    @Test
    public void testReadHierarchy_FileNotFound() {
        Optional<DiscoveryNode> result = reader.readHierarchy();

        MatcherAssert.assertThat(result.isEmpty(), Matchers.is(true));
    }

    @Test
    public void testReadHierarchy_MalformedJson() throws IOException {
        Files.createDirectories(mountPath);
        Files.writeString(mountPath.resolve("hierarchy.json"), "{ invalid json }");

        Optional<DiscoveryNode> result = reader.readHierarchy();

        MatcherAssert.assertThat(result.isEmpty(), Matchers.is(true));
    }

    @Test
    public void testValidateHierarchy_SingleLineage() throws IOException {
        Files.createDirectories(mountPath);

        // Valid single-lineage hierarchy
        String hierarchyJson =
                "{\n"
                        + "  \"name\": \"namespace\",\n"
                        + "  \"nodeType\": \"Namespace\",\n"
                        + "  \"children\": [\n"
                        + "    {\n"
                        + "      \"name\": \"deployment\",\n"
                        + "      \"nodeType\": \"Deployment\",\n"
                        + "      \"children\": []\n"
                        + "    }\n"
                        + "  ]\n"
                        + "}";
        Files.writeString(mountPath.resolve("hierarchy.json"), hierarchyJson);

        Optional<DiscoveryNode> result = reader.readHierarchy();

        MatcherAssert.assertThat(result.isPresent(), Matchers.is(true));
    }

    @Test
    public void testValidateHierarchy_MultipleChildren() throws IOException {
        Files.createDirectories(mountPath);

        // Invalid: node has multiple children
        String hierarchyJson =
                "{\n"
                        + "  \"name\": \"namespace\",\n"
                        + "  \"nodeType\": \"Namespace\",\n"
                        + "  \"children\": [\n"
                        + "    {\n"
                        + "      \"name\": \"deployment1\",\n"
                        + "      \"nodeType\": \"Deployment\",\n"
                        + "      \"children\": []\n"
                        + "    },\n"
                        + "    {\n"
                        + "      \"name\": \"deployment2\",\n"
                        + "      \"nodeType\": \"Deployment\",\n"
                        + "      \"children\": []\n"
                        + "    }\n"
                        + "  ]\n"
                        + "}";
        Files.writeString(mountPath.resolve("hierarchy.json"), hierarchyJson);

        Optional<DiscoveryNode> result = reader.readHierarchy();

        // Should return empty due to validation failure
        MatcherAssert.assertThat(result.isEmpty(), Matchers.is(true));
    }

    @Test
    public void testReadHierarchy_EmptyChildren() throws IOException {
        Files.createDirectories(mountPath);

        String hierarchyJson =
                "{\n"
                        + "  \"name\": \"leaf-node\",\n"
                        + "  \"nodeType\": \"Pod\",\n"
                        + "  \"children\": []\n"
                        + "}";
        Files.writeString(mountPath.resolve("hierarchy.json"), hierarchyJson);

        Optional<DiscoveryNode> result = reader.readHierarchy();

        MatcherAssert.assertThat(result.isPresent(), Matchers.is(true));
        DiscoveryNode node = result.get();
        MatcherAssert.assertThat(node.getName(), Matchers.equalTo("leaf-node"));
        MatcherAssert.assertThat(node.getChildren().isEmpty(), Matchers.is(true));
    }

    @Test
    public void testGetInnermostNode_SingleNode() throws IOException {
        Files.createDirectories(mountPath);

        String hierarchyJson =
                "{\n"
                        + "  \"name\": \"single-node\",\n"
                        + "  \"nodeType\": \"Pod\",\n"
                        + "  \"children\": []\n"
                        + "}";
        Files.writeString(mountPath.resolve("hierarchy.json"), hierarchyJson);

        Optional<DiscoveryNode> hierarchy = reader.readHierarchy();
        MatcherAssert.assertThat(hierarchy.isPresent(), Matchers.is(true));

        DiscoveryNode innermost = reader.getInnermostNode(hierarchy.get());

        MatcherAssert.assertThat(innermost.getName(), Matchers.equalTo("single-node"));
    }

    @Test
    public void testGetInnermostNode_DeepHierarchy() throws IOException {
        Files.createDirectories(mountPath);

        String hierarchyJson =
                "{\n"
                        + "  \"name\": \"namespace\",\n"
                        + "  \"nodeType\": \"Namespace\",\n"
                        + "  \"children\": [\n"
                        + "    {\n"
                        + "      \"name\": \"deployment\",\n"
                        + "      \"nodeType\": \"Deployment\",\n"
                        + "      \"children\": [\n"
                        + "        {\n"
                        + "          \"name\": \"pod\",\n"
                        + "          \"nodeType\": \"Pod\",\n"
                        + "          \"children\": []\n"
                        + "        }\n"
                        + "      ]\n"
                        + "    }\n"
                        + "  ]\n"
                        + "}";
        Files.writeString(mountPath.resolve("hierarchy.json"), hierarchyJson);

        Optional<DiscoveryNode> hierarchy = reader.readHierarchy();
        MatcherAssert.assertThat(hierarchy.isPresent(), Matchers.is(true));

        DiscoveryNode innermost = reader.getInnermostNode(hierarchy.get());

        MatcherAssert.assertThat(innermost.getName(), Matchers.equalTo("pod"));
        MatcherAssert.assertThat(innermost.getNodeType(), Matchers.equalTo("Pod"));
    }
}
