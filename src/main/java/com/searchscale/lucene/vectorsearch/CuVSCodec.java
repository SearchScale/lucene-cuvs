package com.searchscale.lucene.vectorsearch;

import java.lang.invoke.MethodHandles;

import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene99.Lucene99Codec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.searchscale.lucene.vectorsearch.CuVSVectorsWriter.MergeStrategy;

public class CuVSCodec extends Lucene99Codec {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private KnnVectorsFormat defaultKnnVectorsFormat = null;

  public CuVSCodec(int cuvsWriterThreads, int intGraphDegree, int graphDegree, MergeStrategy mergeStrategy) {
    super();
    this.defaultKnnVectorsFormat = new CuVSVectorsFormat(cuvsWriterThreads, intGraphDegree, graphDegree, mergeStrategy);
  }

  public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
    return defaultKnnVectorsFormat;
  }

}
