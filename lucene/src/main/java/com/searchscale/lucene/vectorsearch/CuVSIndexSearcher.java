package com.searchscale.lucene.vectorsearch;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;

import com.searchscale.lucene.vectorsearch.jni.CuVSIndexJni;

public class CuVSIndexSearcher extends IndexSearcher {

  private CuVSIndexJni jni = new CuVSIndexJni();

  public CuVSIndexSearcher(IndexReader reader) {
    super(reader);

    long startTime = System.currentTimeMillis();
    List<Integer> docIds = new ArrayList<>();
    List<float[]> dataVectors = new ArrayList<float[]>();
    try {
      for (LeafReaderContext leaf : reader.leaves()) {
        FloatVectorValues vectors = leaf.reader().getFloatVectorValues(LuceneVectorSearchExample.vectorColName);
        DocIdSetIterator disi = FloatVectorValues.all(leaf.reader().maxDoc());
        for (int doc = disi.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = disi.nextDoc()) {
          vectors.advance(doc);
          docIds.add(leaf.docBase + doc);
          dataVectors.add(vectors.vectorValue().clone());
        }
      }
    } catch (Exception ex) {
      ex.printStackTrace();
    }
    int numVectors = dataVectors.size();
    int dim = dataVectors.get(0).length;
    float[] singleDataVector = new float[numVectors * dim];
    for (int i = 0; i < numVectors; i++) {
      for (int j = 0; j < dim; j++) {
        singleDataVector[i * dim + j] = dataVectors.get(i)[j];
      }
    }
    int docIdsArr[] = new int[docIds.size()];
    for (int i = 0; i < docIdsArr.length; i++)
      docIdsArr[i] = docIds.get(i);
    System.out.println(
        "Time taken for copying data from IndexReader to arrays for C++: " + (System.currentTimeMillis() - startTime));
    startTime = System.currentTimeMillis();
    jni.initIndex(docIdsArr, singleDataVector, docIdsArr.length, dataVectors.get(0).length);
    System.out.println("Time taken for index building: " + (System.currentTimeMillis() - startTime));
  }

  @Override
  public TopDocs search(Query query, int n) throws IOException {
    KnnFloatVectorQuery knnQuery = (KnnFloatVectorQuery) query;
    Object results = jni.getTopK(knnQuery.getTargetCopy(), knnQuery.getK());
    ByteBuffer buf = ((ByteBuffer) results).order(ByteOrder.nativeOrder());
    int N = buf.limit() / 8;
    ScoreDoc scoreDocs[] = new ScoreDoc[N];
    for (int i = 0; i < N; i++) {
      float score = buf.getFloat((i) * 4);
      int id = buf.getInt((N + i) * 4);
      scoreDocs[i] = new ScoreDoc(id, score);
    }
    return new TopDocs(new TotalHits(N, TotalHits.Relation.EQUAL_TO), scoreDocs);
  }
}

