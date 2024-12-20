package com.searchscale.lucene.vectorsearch;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.StackWalker.StackFrame;
import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.index.ByteVectorValues;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.search.KnnCollector;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nvidia.cuvs.CagraIndex;
import com.nvidia.cuvs.CagraQuery;
import com.nvidia.cuvs.CagraSearchParams;
import com.nvidia.cuvs.CuVSResources;


public class CuVSVectorsReader extends KnnVectorsReader {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  IndexInput vectorDataReader = null;
  private IndexInput vectorMetaReader = null;
  public String fileName = null;
  public String metaFileName = null;
  public byte[] indexFileBytes;
  public int[] docIds;
  public float[] vectors;
  public SegmentReadState segmentState = null;
  public int indexFilePayloadSize = 0;
  public long initialFilePointerLoc = 0;
  public SegmentInputStream segmentInputStream;
  public Map<String, Integer> metaMap;
  private Map<String, CagraIndex> cagraIndex;
  private CuVSResources resources;
  
  // public static CuVSIndexJni jni = new CuVSIndexJni();

  public CuVSVectorsReader(SegmentReadState state, CuVSResources resources) throws Throwable {

    segmentState = state;
    this.resources = resources;
    this.cagraIndex = new HashMap<String, CagraIndex>();
    fileName = IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix,
        CuVSVectorsFormat.VECTOR_DATA_EXTENSION);
    metaFileName = IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix,
        CuVSVectorsFormat.META_EXTENSION);

    vectorDataReader = segmentState.directory.openInput(fileName, segmentState.context);
    CodecUtil.readIndexHeader(vectorDataReader);

    vectorMetaReader = segmentState.directory.openInput(metaFileName, segmentState.context);
    CodecUtil.readIndexHeader(vectorMetaReader);

    indexFilePayloadSize = vectorMetaReader.readInt();
    initialFilePointerLoc = vectorDataReader.getFilePointer();
    segmentInputStream = new SegmentInputStream(this, indexFilePayloadSize, initialFilePointerLoc);

    metaMap = Util.deSerializeMapInMemory(
        Util.getZipEntryBAOS(segmentState.segmentInfo.name + ".meta", segmentInputStream).toByteArray());

    List<StackFrame> stackTrace = StackWalker.getInstance().walk(this::getStackTrace);

    boolean isMergeCase = false;
    for (StackFrame s : stackTrace) {
      if (s.toString().startsWith("org.apache.lucene.index.IndexWriter.merge")) {
        isMergeCase = true;
        System.out.println("Reader opening on merge call");
        break;
      }
    }

    if (!isMergeCase) {
      loadIndex();
      //jni.loadIndex(getIndexInputStream());
    }
  }
  
  private void loadIndex() throws IOException, Throwable {
    
    ZipInputStream zis = getIndexInputStream();
    ZipEntry ze;
    while ((ze = zis.getNextEntry()) != null) {
      String en = ze.getName();
      if (en.endsWith(".meta")) {
        // Do nothing for now.
      } else if (en.endsWith(".vec")) {
        // Do nothing for now.
      } else if (en.endsWith(".cag")) {
        
        // This InputStream -> OutputStream -> InputStream is redundant, should be removed.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len = 0;
        while ((len = zis.read(buffer)) != -1) {
          baos.write(buffer, 0, len);
        }
        
        InputStream is = new ByteArrayInputStream(baos.toByteArray());
        
        cagraIndex.put(en, new CagraIndex.Builder(resources)
            .from(is)
            .build());
      }
    }
  }

  public List<StackFrame> getStackTrace(Stream<StackFrame> stackFrameStream) {
    return stackFrameStream.collect(Collectors.toList());
  }

  public ZipInputStream getIndexInputStream() throws IOException {
    segmentInputStream.reset();
    return new ZipInputStream(segmentInputStream);
  }

  @Override
  public void close() throws IOException {
    IOUtils.close(vectorDataReader);
    IOUtils.close(vectorMetaReader);
    System.gc();
  }

  @Override
  public long ramBytesUsed() {
    // TODO: Pending implementation
    return 0;
  }

  @Override
  public void checkIntegrity() throws IOException {
    // TODO: Pending implementation
  }

  @Override
  public FloatVectorValues getFloatVectorValues(String field) throws IOException {
    // TODO: may need implementation
    throw new UnsupportedOperationException();
  }

  @Override
  public ByteVectorValues getByteVectorValues(String field) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void search(String field, float[] target, KnnCollector knnCollector, Bits acceptDocs) throws IOException {
    PerLeafCuVSKnnCollector collector = (PerLeafCuVSKnnCollector) knnCollector;
    //jni.getTopK(target, collector.k(), collector.iTopK, collector.searchWidth, knnCollector, metaMap, field, false);

    int prevDocCount = 0;
    CagraSearchParams searchParams = new CagraSearchParams.Builder(resources)
        .withItopkSize(collector.iTopK)
        .withSearchWidth(collector.searchWidth)
        .build();
    CagraQuery query = new CagraQuery.Builder()
        .withTopK(collector.k())
        .withSearchParams(searchParams)
        .withQueryVectors(new float[][] {target})
        .build();
    
    for (Map.Entry<String, Integer> entry : metaMap.entrySet()) {
      String segmentMapKey = entry.getKey() + "_" + field + ".cag";
      try {
        List<Map<Integer, Float>> results = cagraIndex.get(segmentMapKey).search(query).getResults();
        for(Map<Integer, Float> result : results) { // List expected to have only one entry because of single query "target".
          for(Entry<Integer, Float> kv : result.entrySet()) {
            collector.addScoreDoc(prevDocCount, kv.getKey(), kv.getValue());
          }
        }
      } catch (Throwable e) {
        e.printStackTrace();
      }
      prevDocCount += entry.getValue();
    }
  }
  
  @Override
  public void search(String field, byte[] target, KnnCollector knnCollector, Bits acceptDocs) throws IOException {
    throw new UnsupportedOperationException();
  }
}
