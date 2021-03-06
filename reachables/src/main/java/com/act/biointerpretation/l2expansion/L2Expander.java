/*************************************************************************
*                                                                        *
*  This file is part of the 20n/act project.                             *
*  20n/act enables DNA prediction for synthetic biology/bioengineering.  *
*  Copyright (C) 2017 20n Labs, Inc.                                     *
*                                                                        *
*  Please direct all queries to act@20n.com.                             *
*                                                                        *
*  This program is free software: you can redistribute it and/or modify  *
*  it under the terms of the GNU General Public License as published by  *
*  the Free Software Foundation, either version 3 of the License, or     *
*  (at your option) any later version.                                   *
*                                                                        *
*  This program is distributed in the hope that it will be useful,       *
*  but WITHOUT ANY WARRANTY; without even the implied warranty of        *
*  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
*  GNU General Public License for more details.                          *
*                                                                        *
*  You should have received a copy of the GNU General Public License     *
*  along with this program.  If not, see <http://www.gnu.org/licenses/>. *
*                                                                        *
*************************************************************************/

package com.act.biointerpretation.l2expansion;

import chemaxon.reaction.ReactionException;
import com.act.biointerpretation.sars.NoSar;
import com.act.biointerpretation.sars.Sar;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public abstract class L2Expander implements Serializable {
  private static final long serialVersionUID = 5846728290095735668L;

  private static final Logger LOGGER = LogManager.getFormatterLogger(L2Expander.class);

  // This SAR accepts every substrate.
  @JsonIgnore
  protected static final List<Sar> NO_SAR = Collections.unmodifiableList(Collections.singletonList(new NoSar()));

  private PredictionGenerator generator;

  public abstract Iterable<PredictionSeed> getPredictionSeeds();

  public L2Expander(PredictionGenerator generator) {
    this.generator = generator;
  }

  /**
   * Get predictions for this expander without logging progress.
   *
   * @return A corpus of L2 predictions using the specified generator.
   */
  public L2PredictionCorpus getPredictions() {
    return getPredictions(Optional.empty());
  }

  /**
   * Get predictions for this expander, logging progress to the specified output stream.
   *
   * @param maybeOutputStream A stream to which to write incremental results.
   * @return A corpus of L2 predicitions using the specified generator.
   */
  public L2PredictionCorpus getPredictions(Optional<OutputStream> maybeOutputStream) {
    L2PredictionCorpus result = new L2PredictionCorpus();

    Optional<OutputStreamWriter> maybeWriter = maybeOutputStream.map(OutputStreamWriter::new);

    ObjectMapper objectMapper = new ObjectMapper();

    int counter = 0;
    for (PredictionSeed seed : getPredictionSeeds()) {
      if (counter % 1000 == 0) {
        LOGGER.info("Processed %d seeds", counter);
      }
      counter++;

      // Apply reactor to substrate if possible
      try {
        List<L2Prediction> results = generator.getPredictions(seed);
        if (maybeWriter.isPresent()) {
          try {
            /* Write results as string to ensure the object mapper doesn't close the stream we give it.  See
             * https://fasterxml.github.io/jackson-databind/javadoc/2.6/com/fasterxml/jackson/databind/ObjectMapper.html#writeValue-java.io.OutputStream-java.lang.Object-
             * for a confusing explanation of why we worry about this. */
            String resultJson = objectMapper.writeValueAsString(results);
            maybeWriter.get().write(resultJson);
            maybeWriter.get().write("\n");
            maybeWriter.get().flush(); // Flush to ensure the user can actually see the progress output.
          } catch (Exception e) {
            LOGGER.error("Caught exception when writing progress, skipping: %s", e.getMessage());
          }
        }
        result.addAll(results);
        // If there is an error on a certain RO, metabolite pair, we should log the error, but the expansion may
        // produce some valid results, so no error is thrown.
      } catch (ReactionException e) {
        LOGGER.error("ReactionException on getPredictions. %s", e.getMessage());
      } catch (IOException e) {
        LOGGER.error("IOException during prediction generation. %s", e.getMessage());
      }
    }

    return result;
  }
}
