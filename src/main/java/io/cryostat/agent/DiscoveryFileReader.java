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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import io.cryostat.agent.model.DiscoveryMetadata;
import io.cryostat.agent.model.DiscoveryNode;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for reading discovery metadata and hierarchy files mounted by an external process
 * (typically Cryostat Operator). Provides graceful degradation when files are missing or invalid.
 */
public class DiscoveryFileReader {

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryFileReader.class);

    private final Path mountPath;
    private final String metadataFilename;
    private final String hierarchyFilename;
    private final ObjectMapper mapper;

    public DiscoveryFileReader(
            String mountPath, String metadataFilename, String hierarchyFilename) {
        this.mountPath = Paths.get(mountPath);
        this.metadataFilename = metadataFilename;
        this.hierarchyFilename = hierarchyFilename;
        this.mapper = new ObjectMapper();
    }

    /**
     * Reads the metadata file containing labels and annotations to be applied to the Agent's
     * DiscoveryNode.
     *
     * @return Optional containing the metadata if successfully read, empty otherwise
     */
    public Optional<DiscoveryMetadata> readMetadata() {
        Path metadataPath = mountPath.resolve(metadataFilename);

        if (!Files.exists(metadataPath)) {
            logger.debug(
                    "Discovery metadata file not found at {}, using default metadata",
                    metadataPath);
            return Optional.empty();
        }

        try (InputStream is = Files.newInputStream(metadataPath)) {
            DiscoveryMetadata metadata = mapper.readValue(is, DiscoveryMetadata.class);
            logger.info("Successfully loaded discovery metadata from {}", metadataPath);
            return Optional.of(metadata);
        } catch (IOException e) {
            logger.error(
                    "Failed to read or parse discovery metadata file at {}: {}",
                    metadataPath,
                    e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Reads the hierarchy file containing a tree of DiscoveryNodes that wrap around the Agent's
     * self node. Validates that the hierarchy follows single-lineage constraint (each node has 0 or
     * 1 children).
     *
     * @return Optional containing the root hierarchy node if successfully read and validated, empty
     *     otherwise
     */
    public Optional<DiscoveryNode> readHierarchy() {
        Path hierarchyPath = mountPath.resolve(hierarchyFilename);

        if (!Files.exists(hierarchyPath)) {
            logger.debug(
                    "Discovery hierarchy file not found at {}, using flat structure",
                    hierarchyPath);
            return Optional.empty();
        }

        try (InputStream is = Files.newInputStream(hierarchyPath)) {
            DiscoveryNode hierarchy = mapper.readValue(is, DiscoveryNode.class);

            if (!validateSingleLineage(hierarchy)) {
                logger.error(
                        "Discovery hierarchy at {} violates single-lineage constraint (each node"
                                + " must have 0 or 1 children)",
                        hierarchyPath);
                return Optional.empty();
            }

            logger.info("Successfully loaded discovery hierarchy from {}", hierarchyPath);
            return Optional.of(hierarchy);
        } catch (IOException e) {
            logger.error(
                    "Failed to read or parse discovery hierarchy file at {}: {}",
                    hierarchyPath,
                    e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Validates that a hierarchy follows the single-lineage constraint: each node must have at most
     * one child.
     *
     * @param node the root node to validate
     * @return true if the hierarchy is valid, false otherwise
     */
    private boolean validateSingleLineage(DiscoveryNode node) {
        if (node == null) {
            return true;
        }

        if (node.getChildren().size() > 1) {
            logger.warn(
                    "Node '{}' has {} children, violating single-lineage constraint",
                    node.getName(),
                    node.getChildren().size());
            return false;
        }

        // Recursively validate children
        for (DiscoveryNode child : node.getChildren()) {
            if (!validateSingleLineage(child)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Finds the innermost (deepest) node in a hierarchy by following the single child path. This is
     * where the Agent's self node should be attached.
     *
     * @param root the root node of the hierarchy
     * @return the innermost node (leaf node in the single-lineage tree)
     */
    public DiscoveryNode getInnermostNode(DiscoveryNode root) {
        if (root == null) {
            throw new IllegalArgumentException("Root node cannot be null");
        }

        DiscoveryNode current = root;
        while (!current.getChildren().isEmpty()) {
            current = current.getChildren().get(0);
        }

        return current;
    }
}
