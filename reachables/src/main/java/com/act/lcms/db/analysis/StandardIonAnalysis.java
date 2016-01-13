package com.act.lcms.db.analysis;

import com.act.lcms.db.io.DB;
import com.act.lcms.db.io.LoadPlateCompositionIntoDB;
import com.act.lcms.db.model.ChemicalAssociatedWithPathway;
import com.act.lcms.db.model.ConstructEntry;
import com.act.lcms.db.model.Plate;
import com.act.lcms.db.model.ScanFile;
import com.act.lcms.db.model.StandardWell;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StandardIonAnalysis {
  public static final String OPTION_DIRECTORY = "d";
  public static final String OPTION_CONSTRUCT = "c";
  public static final String OPTION_NEGATIVE_CONSTRUCTS = "C";
  public static final String OPTION_OUTPUT_PREFIX = "o";

  public static final String HELP_MESSAGE = StringUtils.join(new String[]{
      "TODO: write a help message."
  }, "");
  public static final HelpFormatter HELP_FORMATTER = new HelpFormatter();

  static {
    HELP_FORMATTER.setWidth(100);
  }

  public static final List<Option.Builder> OPTION_BUILDERS = new ArrayList<Option.Builder>() {{
    add(Option.builder(OPTION_DIRECTORY)
            .argName("directory")
            .desc("The directory where LCMS analysis results live")
            .hasArg().required()
            .longOpt("data-dir")
    );
    add(Option.builder(OPTION_CONSTRUCT)
            .argName("construct")
            .desc("The construct whose pathway chemicals should be analyzed")
            .hasArg().required()
            .longOpt("construct")
    );
  }};
  static {
    // Add DB connection options.
    OPTION_BUILDERS.addAll(DB.DB_OPTION_BUILDERS);
  }

  /**
   * Given a construct id (like "pa1"), return the associated ConstructEntry object and a list of the chemical
   * products/byproducts associated with that pathway (including all intermediate and side-reaction products).
   * @param db The DB connection to query.
   * @param constructId The identifier for the constructs whose products should be queried (like "pa1").
   * @return A pair of the ConstructEntry for the specified construct id and a list of chemical products associated
   *         with that pathway.
   * @throws SQLException
   */
  public Pair<ConstructEntry, List<ChemicalAssociatedWithPathway>> getChemicalsForConstruct(DB db, String constructId)
    throws SQLException {
    ConstructEntry construct =
        ConstructEntry.getInstance().getCompositionMapEntryByCompositionId(db, constructId);
    if (construct == null) {
      throw new RuntimeException(String.format("Unable to find construct '%s'", constructId));
    }

    List<ChemicalAssociatedWithPathway> products =
        ChemicalAssociatedWithPathway.getInstance().getChemicalsAssociatedWithPathwayByConstructId(db, constructId);

    return Pair.of(construct, products);
  }

  /**
   * Find all standard wells containing a specified chemical that is associated with a construct's pathway.
   * @param db The DB connection to query.
   * @param pathwayChem The chemical for which to find standard wells.
   * @return A list of standard wells (in any plate) containing the specified chemical.
   * @throws SQLException
   */
  public List<StandardWell> getStandardWellsForChemical(DB db, ChemicalAssociatedWithPathway pathwayChem)
      throws SQLException {
    return StandardWell.getInstance().getStandardWellsByChemical(db, pathwayChem.getChemical());
  }

  public List<StandardWell> getViableNegativeControlsForStandardWell(DB db, StandardWell baseStandard)
      throws SQLException {
    List<StandardWell> wellsFromSamePlate = StandardWell.getInstance().getByPlateId(db, baseStandard.getPlateId());

    // TODO: take availability of scan files into account here?
    List<StandardWell> candidates = new ArrayList<>();
    for (StandardWell well : wellsFromSamePlate) {
      if (well.getChemical().equals(baseStandard.getChemical())) {
        continue; // Skip wells with the same chemical.
      }

      if (baseStandard.getConcentration() != null && well.getConcentration() != null &&
          !baseStandard.getConcentration().equals(well.getConcentration())) {
        continue; // Skip non-matching concentrations if both wells define concentration.
      }

      if (baseStandard.getMedia() != null && well.getMedia() != null &&
          !baseStandard.getMedia().equals(well.getMedia())) {
        continue; // Skip non-matching media if both wells define media type.
      }
      candidates.add(well);
    }

    return candidates;
  }

  /**
   * Given a standard well and viable negative control candidates, returns a map of mapping of all specified standard
   * wells to scan files sharing the ion modes available for the specified standard well.  For example, if the specified
   * standard well has only positive ion mode scan files available, the map will contain only positive ion mode scan
   * files for that well and all specified negativeCandidate wells.  If both positive and negative ion mode scan files
   * are available for the specified well, then, both positive and negative mode scan files will be included in the map.
   * @param db The DB connection to query.
   * @param primaryStandard The primary standard well being analysed.
   * @param negativeCandidates A list of standard wells that could be used as negative controls in the analysis.
   * @return A map from all specified standard wells (primary and negative controls) to a list of scan files.
   * @throws SQLException
   */
  public Map<StandardWell, List<ScanFile>> getViableScanFilesForStandardWells(
      DB db, StandardWell primaryStandard, List<StandardWell> negativeCandidates) throws SQLException {
    Map<StandardWell, List<ScanFile>> wellToFilesMap = new HashMap<>();
    List<ScanFile> posScanFiles = ScanFile.getScanFileByPlateIDRowAndColumn(
        db, primaryStandard.getPlateId(), primaryStandard.getPlateRow(), primaryStandard.getPlateColumn());
    wellToFilesMap.put(primaryStandard, posScanFiles);

    Set<ScanFile.SCAN_MODE> viableScanModes = new HashSet<>();
    for (ScanFile file : posScanFiles) {
      viableScanModes.add(file.getMode());
    }

    for (StandardWell well : negativeCandidates) {
      List<ScanFile> allScanFiles = ScanFile.getScanFileByPlateIDRowAndColumn(
          db, well.getPlateId(), well.getPlateRow(), well.getPlateColumn());
      List<ScanFile> viableScanFiles = new ArrayList<>();
      for (ScanFile file : allScanFiles) {
        if (viableScanModes.contains(file.getMode())) {
          viableScanFiles.add(file);
        }
      }
      wellToFilesMap.put(well, viableScanFiles);
    }

    return wellToFilesMap;
  }

  public static void main(String[] args) throws Exception {
    Options opts = new Options();
    for (Option.Builder b : OPTION_BUILDERS) {
      opts.addOption(b.build());
    }

    CommandLine cl = null;
    try {
      CommandLineParser parser = new DefaultParser();
      cl = parser.parse(opts, args);
    } catch (ParseException e) {
      System.err.format("Argument parsing failed: %s\n", e.getMessage());
      HELP_FORMATTER.printHelp(LoadPlateCompositionIntoDB.class.getCanonicalName(), HELP_MESSAGE, opts, null, true);
      System.exit(1);
    }

    if (cl.hasOption("help")) {
      HELP_FORMATTER.printHelp(LoadPlateCompositionIntoDB.class.getCanonicalName(), HELP_MESSAGE, opts, null, true);
      return;
    }

    File lcmsDir = new File(cl.getOptionValue(OPTION_DIRECTORY));
    if (!lcmsDir.isDirectory()) {
      System.err.format("File at %s is not a directory\n", lcmsDir.getAbsolutePath());
      HELP_FORMATTER.printHelp(LoadPlateCompositionIntoDB.class.getCanonicalName(), HELP_MESSAGE, opts, null, true);
      System.exit(1);
    }

    try (DB db = DB.openDBFromCLI(cl)) {

      StandardIonAnalysis analysis = new StandardIonAnalysis();

      Pair<ConstructEntry, List<ChemicalAssociatedWithPathway>> constructAndPathwayChems =
          analysis.getChemicalsForConstruct(db, cl.getOptionValue(OPTION_CONSTRUCT));
      System.out.format("Construct: %s\n", constructAndPathwayChems.getLeft().getCompositionId());
      Map<Integer, Plate> plateCache = new HashMap<>();

      for (ChemicalAssociatedWithPathway pathwayChem : constructAndPathwayChems.getRight()) {
        System.out.format("  Pathway chem %s\n", pathwayChem.getChemical());
        List<StandardWell> standardWells = analysis.getStandardWellsForChemical(db, pathwayChem);

        for (StandardWell wellToAnalyze : standardWells) {
          List<StandardWell> negativeControls = analysis.getViableNegativeControlsForStandardWell(db, wellToAnalyze);
          Map<StandardWell, List<ScanFile>> allViableScanFiles =
              analysis.getViableScanFilesForStandardWells(db, wellToAnalyze, negativeControls);

          List<String> primaryStandardScanFileNames = new ArrayList<>();
          for (ScanFile scanFile : allViableScanFiles.get(wellToAnalyze)) {
            primaryStandardScanFileNames.add(scanFile.getFilename());
          }
          Plate plate = plateCache.get(wellToAnalyze.getPlateId());
          if (plate == null) {
            plate = Plate.getPlateById(db, wellToAnalyze.getPlateId());
            plateCache.put(plate.getId(), plate);
          }

          System.out.format("    Standard well: %s @ %s, '%s'%s%s\n", plate.getBarcode(),
              wellToAnalyze.getCoordinatesString(),
              wellToAnalyze.getChemical(),
              wellToAnalyze.getMedia() == null ? "" : String.format(" in %s", wellToAnalyze.getMedia()),
              wellToAnalyze.getConcentration() == null ? "" : String.format(" @ %s", wellToAnalyze.getConcentration()));
          System.out.format("      Scan files: %s\n", StringUtils.join(primaryStandardScanFileNames, ", "));

          for (StandardWell negCtrlWell : negativeControls) {
            plate = plateCache.get(negCtrlWell.getPlateId());
            if (plate == null) {
              plate = Plate.getPlateById(db, negCtrlWell.getPlateId());
              plateCache.put(plate.getId(), plate);
            }
            List<String> negativeControlScanFileNames = new ArrayList<>();
            for (ScanFile scanFile : allViableScanFiles.get(negCtrlWell)) {
              negativeControlScanFileNames.add(scanFile.getFilename());
            }

            System.out.format("      Viable negative: %s @ %s, '%s'%s%s\n", plate.getBarcode(),
                negCtrlWell.getCoordinatesString(),
                negCtrlWell.getChemical(),
                negCtrlWell.getMedia() == null ? "" : String.format(" in %s", negCtrlWell.getMedia()),
                negCtrlWell.getConcentration() == null ? "" : String.format(" @ %s", negCtrlWell.getConcentration()));
            System.out.format("        Scan files: %s\n", StringUtils.join(negativeControlScanFileNames, ", "));
            // TODO: do something useful with the standard wells and their scan files, and then stop all the printing.
          }
        }
      }
    }
  }
}