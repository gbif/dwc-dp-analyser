package org.gbif.dp.analysis;

import org.gbif.dp.analysis.api.DatapackageAnalysisResult;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

public interface DataPackageAnalyser {

  DatapackageAnalysisResult analyse(Path descriptorPath, org.gbif.dp.analysis.api.ValidationOptions options, List<org.gbif.dp.analysis.api.AnalysisFeature> analysisFeatures)
          throws IOException, SQLException;
}

