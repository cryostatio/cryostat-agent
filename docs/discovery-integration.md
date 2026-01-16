# Discovery Integration with Cryostat Operator

## Overview

The Cryostat Agent supports integration with the Cryostat Operator to automatically include Kubernetes context in its discovery information. The Operator can mount JSON files containing metadata and hierarchy information that the Agent will incorporate into its published discovery tree.

## Configuration

The following configuration properties control the discovery file integration:

| Property | Default | Description |
|----------|---------|-------------|
| `cryostat.agent.discovery.mount-path` | `/tmp/cryostat-agent/discovery` | Directory where discovery files are mounted |
| `cryostat.agent.discovery.metadata-filename` | `metadata.json` | Filename for metadata (labels and annotations) |
| `cryostat.agent.discovery.hierarchy-filename` | `hierarchy.json` | Filename for hierarchy structure |

## File Formats

### metadata.json

This file contains labels and annotations that should be applied to the Agent's own DiscoveryNode and Target.

**Schema:**
```json
{
  "labels": {
    "key1": "value1",
    "key2": "value2"
  },
  "annotations": {
    "annotation1": "value1",
    "annotation2": "value2"
  }
}
```

**Fields:**
- `labels` (optional): Map of string key-value pairs representing Kubernetes labels
- `annotations` (optional): Map of string key-value pairs representing Kubernetes annotations

**Example:**
```json
{
  "labels": {
    "app": "my-application",
    "version": "1.0.0",
    "environment": "production"
  },
  "annotations": {
    "prometheus.io/scrape": "true",
    "prometheus.io/port": "8080",
    "description": "Main application service"
  }
}
```

**Behavior:**
- Labels are applied to both the DiscoveryNode and Target objects
- Annotations are merged into the Target's platform annotations section
- Mounted metadata takes precedence over agent-generated metadata on conflicts
- Missing file results in debug log and default behavior (no additional metadata)

### hierarchy.json

This file defines a tree structure of DiscoveryNodes that wrap around the Agent's self node. This represents the Kubernetes object hierarchy (Namespace -> Deployment -> Pod -> Agent).

**Schema:**
```json
{
  "name": "string",
  "nodeType": "string",
  "labels": {
    "key": "value"
  },
  "children": [
    {
      "name": "string",
      "nodeType": "string",
      "labels": {},
      "children": []
    }
  ]
}
```

**Fields:**
- `name` (required): Name of the discovery node
- `nodeType` (required): Type identifier (e.g., "Namespace", "Deployment", "Pod")
- `labels` (optional): Map of string key-value pairs for this node
- `children` (optional): Array of child DiscoveryNode objects

**Constraints:**
- **Single-lineage only**: Each node must have 0 or 1 children (not multiple)
- Hierarchy nodes must NOT have `target` fields (only leaf nodes have targets)
- The Agent's self node(s) will be attached as children of the innermost node

**Example:**
```json
{
  "name": "production-namespace",
  "nodeType": "Namespace",
  "labels": {
    "kubernetes.io/metadata.name": "production"
  },
  "children": [
    {
      "name": "my-app-deployment",
      "nodeType": "Deployment",
      "labels": {
        "app": "my-application",
        "app.kubernetes.io/name": "my-app"
      },
      "children": [
        {
          "name": "my-app-pod-abc123",
          "nodeType": "Pod",
          "labels": {
            "app": "my-application",
            "pod-template-hash": "abc123"
          }
        }
      ]
    }
  ]
}
```

**Behavior:**
- The Agent's self DiscoveryNode(s) are attached as children of the innermost hierarchy node
- The complete tree (Namespace -> Deployment -> Pod -> Agent) is published to Cryostat
- Missing file results in debug log and default behavior (no hierarchy wrapping)
- Invalid hierarchy (multiple children per node) results in error log and file is ignored

## Integration Flow

1. **Agent Startup**: Agent reads configuration properties for mount path and filenames
2. **Self Definition**: When defining itself, the Agent:
   - Reads `metadata.json` if present
   - Merges labels and annotations into its DiscoveryNode and Target
3. **Registration Update**: When publishing to Cryostat, the Agent:
   - Reads `hierarchy.json` if present
   - Validates single-lineage constraint
   - Attaches its self node(s) as children of the innermost hierarchy node
   - Publishes the complete hierarchy tree

## Error Handling

- **Missing files**: Debug log, graceful degradation (no additional metadata/hierarchy)
- **Malformed JSON**: Error log with parse details, file is ignored
- **Invalid hierarchy**: Error log, file is ignored
- **Missing required fields**: Deserialization fails, error log, file is ignored

## Example Use Case

**Scenario**: A Java application running in a Kubernetes Pod

**Operator mounts:**
- `/tmp/cryostat-agent/discovery/metadata.json` with Pod labels and annotations
- `/tmp/cryostat-agent/discovery/hierarchy.json` with Namespace -> Deployment -> Pod structure

**Result**: Cryostat receives a discovery tree showing:
```
production-namespace (Namespace)
└── my-app-deployment (Deployment)
    └── my-app-pod-abc123 (Pod)
        └── my-application:8080 (JVM Agent)
```
