package org.gbif.dp.descriptor;

import java.util.List;

public record DataPackageDescriptor(
    String name,
    List<ResourceDescriptor> resources
    ) {}

