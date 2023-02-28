# gdd-batch-app

![Java 19](https://img.shields.io/badge/Java-19-lightgrey)
![GSON](https://img.shields.io/badge/GSON-v2.10.1-informational)
![GSON (latest)](https://img.shields.io/maven-central/v/com.google.code.gson/gson?label=latest)

This command line application streamlines downloading (saving) inventories of files. It sends the unique inventory identifier (typically an URL) to the configured web service, retrieves individual file details and proceeds to download them. By default, it integrates with two platforms that provide data that is in the public domain.

You should have already received instructions on how to use this application (potentially including `GDD_SERVICE_URL` and how to set it) via a different channel.

## Overview

The configured web service (`GDD_SERVICE_URL`) is responsible for generating the list of URLs (and associated metadata) that this application can then use to download files.

```mermaid
sequenceDiagram;
    participant C as gdd-batch-app;
    participant S as GDD_SERVICE_URL;
    participant IS1 as Inventory Provider;

    C->>S: Inventory ID (URL);
    S->>IS1: Inventory ID;
    IS1->>S: Inventory details;
    S->>C: Inventory details (normalized);
    C->>IS1: Inventory file URL 1;
    IS1->>C: Inventory file 1;
    C-->>IS1: …;
    IS1->>C: Inventory file N;
```
