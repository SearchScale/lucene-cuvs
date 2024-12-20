package com.searchscale.lucene.vectorsearch;

import java.io.IOException;
import java.util.Comparator;
import java.util.PriorityQueue;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.util.Bits;
import org.apache.lucene.search.knn.KnnCollectorManager;

public class CuVSKnnFloatVectorQuery extends KnnFloatVectorQuery {

  final private int iTopK;
  final private int searchWidth;

  public CuVSKnnFloatVectorQuery(String field, float[] target, int k, int iTopK, int searchWidth) {
    super(field, target, k);
    this.iTopK = iTopK;
    this.searchWidth = searchWidth;
  }

  @Override
  protected TopDocs approximateSearch(LeafReaderContext context, Bits acceptDocs, int visitedLimit, KnnCollectorManager knnCollectorManager) throws IOException {

    PerLeafCuVSKnnCollector results = new PerLeafCuVSKnnCollector(k, iTopK, searchWidth);

    context.reader().searchNearestVectors(field, this.getTargetCopy(), results, null);
    return results.topDocs();
  }

}
