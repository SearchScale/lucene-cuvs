package com.searchscale.lucene.vectorsearch;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.KnnFieldVectorsWriter;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat.FieldsReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.Sorter.DocMap;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nvidia.cuvs.CagraIndex;
import com.nvidia.cuvs.CagraIndexParams;
import com.nvidia.cuvs.CagraIndexParams.CagraGraphBuildAlgo;
import com.nvidia.cuvs.CuVSResources;

public class CuVSVectorsWriter extends KnnVectorsWriter {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private List<CagraFieldVectorsWriter> fields = new ArrayList<>();
  private IndexOutput vectorIndex = null;
  private IndexOutput vectorIndexMetaFile = null;
  private SegmentWriteState segmentWriteState = null;
  private String vectorDataFileName = null;
  private String vectorDataMetaFileName = null;
  //public static CuVSIndexJni jni = new CuVSIndexJni();
  private CagraIndex cagraIndex;
  private int cuvsWriterThreads;
  private int intGraphDegree;
  private int graphDegree;
  private MergeStrategy mergeStrategy;
  private CuVSResources resources;

  public enum MergeStrategy {
    NO_MERGE, TRIVIAL_MERGE, NON_TRIVIAL_MERGE
  };

  public CuVSVectorsWriter(SegmentWriteState state, int cuvsWriterThreads, int intGraphDegree, int graphDegree, MergeStrategy mergeStrategy, CuVSResources resources)
      throws IOException {
    super();
    this.segmentWriteState = state;
    this.mergeStrategy = mergeStrategy;
    this.cuvsWriterThreads = cuvsWriterThreads;
    this.intGraphDegree = intGraphDegree;
    this.graphDegree = graphDegree;
    this.resources = resources;
    vectorDataFileName = IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix,
        CuVSVectorsFormat.VECTOR_DATA_EXTENSION);
    vectorDataMetaFileName = IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix,
        CuVSVectorsFormat.META_EXTENSION);
    vectorIndex = state.directory.createOutput(vectorDataFileName, state.context);
    vectorIndexMetaFile = state.directory.createOutput(vectorDataMetaFileName, state.context);
    CodecUtil.writeIndexHeader(vectorIndex, CuVSVectorsFormat.VECTOR_DATA_CODEC_NAME, CuVSVectorsFormat.VERSION_CURRENT,
        state.segmentInfo.getId(), state.segmentSuffix);
    CodecUtil.writeIndexHeader(vectorIndexMetaFile, CuVSVectorsFormat.VECTOR_DATA_CODEC_NAME,
        CuVSVectorsFormat.VERSION_CURRENT, state.segmentInfo.getId(), state.segmentSuffix);
  }

  @Override
  public long ramBytesUsed() {
    return 0;
  }

  @Override
  public void close() throws IOException {
    IOUtils.close(vectorIndex);
    IOUtils.close(vectorIndexMetaFile);
    vectorIndex = null;
    vectorIndexMetaFile = null;
    fields.clear();
    fields = null;
    System.gc();
  }

  @Override
  public KnnFieldVectorsWriter<?> addField(FieldInfo fieldInfo) throws IOException {
    CagraFieldVectorsWriter cagraFieldVectorWriter = new CagraFieldVectorsWriter(fieldInfo);
    fields.add(cagraFieldVectorWriter);
    return cagraFieldVectorWriter;
  }
  
  private byte[] createCagraIndex(List<float[]> fieldVectors) throws Throwable {
    float vectors[][] = new float[fieldVectors.size()][fieldVectors.get(0).length];
        for (int i=0; i<vectors.length; i++) {
          for (int j=0; j<vectors[i].length; j++) {
            vectors[i][j] = fieldVectors.get(i)[j];
          }
        }
        CagraIndexParams indexParams = new CagraIndexParams.Builder(resources)
            .withNumWriterThreads(cuvsWriterThreads)
            .withIntermediateGraphDegree(intGraphDegree)
            .withGraphDegree(graphDegree)
            .withCagraGraphBuildAlgo(CagraGraphBuildAlgo.NN_DESCENT)
            .build();
        
        log.info("Indexing started: " + System.currentTimeMillis());
        cagraIndex = new CagraIndex.Builder(resources)
            .withDataset(vectors)
            .withIndexParams(indexParams)
            .build();
        log.info("Indexing done: " + System.currentTimeMillis());
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        cagraIndex.serialize(baos, new File("tmpindex.cag"));
        byte bx[] = baos.toByteArray();
    return bx;
  }

  @SuppressWarnings("resource")
  @Override
  public void flush(int maxDoc, DocMap sortMap) throws IOException {

    long s = vectorIndex.getFilePointer();
    ZipOutputStream zos = new ZipOutputStream(new SegmentOutputStream(vectorIndex, 100000));
    zos.setLevel(Deflater.NO_COMPRESSION);

    Map<String, Integer> metaMap = new LinkedHashMap<String, Integer>();

    for (CagraFieldVectorsWriter field : fields) {
      long start = System.currentTimeMillis();
      // TODO: This too can be improved by returning an input stream object and
      // reading from it rather than getting the index file in a byte array.
      //byte[] bx = jni.initIndex(field.fieldVectorDimension, field.vectors, cuvsWriterThreads, intGraphDegree, graphDegree);
      //log.info("init() time: " + (System.currentTimeMillis() - start));

      byte[] bx = null;
      try {
        bx = createCagraIndex(field.vectors);
      } catch (Throwable e) {
        e.printStackTrace();
      }
      
      start = System.currentTimeMillis();
      ZipEntry indexFileZipEntry = new ZipEntry(segmentWriteState.segmentInfo.name + "_" + field.fieldName + ".cag");
      zos.putNextEntry(indexFileZipEntry);
      zos.write(bx, 0, bx.length);
      zos.closeEntry();

      log.info("time for writing index to zip: " + (System.currentTimeMillis() - start));
      start = System.currentTimeMillis();

      ZipEntry vectorFileZipEntry = new ZipEntry(segmentWriteState.segmentInfo.name + "_" + field.fieldName + ".vec");
      zos.putNextEntry(vectorFileZipEntry);
      new ObjectOutputStream(zos).writeObject(field.vectors); // NooooOOOOOoooooooooo java object serialization please
      zos.closeEntry();
      field.vectors.clear();

      log.info("list serializing and writing: " + (System.currentTimeMillis() - start));
    }

    metaMap.put(segmentWriteState.segmentInfo.name, maxDoc);
    ZipEntry metaFileZipEntry = new ZipEntry(segmentWriteState.segmentInfo.name + ".meta");
    zos.putNextEntry(metaFileZipEntry);
    new ObjectOutputStream(zos).writeObject(metaMap);
    zos.closeEntry();

    zos.close();

    long e = vectorIndex.getFilePointer();
    vectorIndexMetaFile.writeInt((int) (e - s));
  }

  @Override
  public void finish() throws IOException {
    CodecUtil.writeFooter(vectorIndex);
    CodecUtil.writeFooter(vectorIndexMetaFile);
  }

  @SuppressWarnings("resource")
  @Override
  public void mergeOneField(FieldInfo fieldInfo, MergeState mergeState) throws IOException {

    List<SegmentInputStream> segInputStreams = new ArrayList<SegmentInputStream>();

    for (int i = 0; i < mergeState.knnVectorsReaders.length; i++) {
      CuVSVectorsReader reader = ((CuVSVectorsReader) ((FieldsReader) mergeState.knnVectorsReaders[i])
          .getFieldReader(fieldInfo.getName()));
      segInputStreams.add(reader.segmentInputStream);
    }

    String tempVectorDataFileName = IndexFileNames.segmentFileName(segmentWriteState.segmentInfo.name + "_temp",
        segmentWriteState.segmentSuffix, CuVSVectorsFormat.VECTOR_DATA_EXTENSION);
    IndexOutput tempVectorIndex = segmentWriteState.directory.createOutput(tempVectorDataFileName,
        segmentWriteState.context);
    CodecUtil.writeIndexHeader(tempVectorIndex, CuVSVectorsFormat.VECTOR_DATA_CODEC_NAME,
        CuVSVectorsFormat.VERSION_CURRENT, segmentWriteState.segmentInfo.getId(), segmentWriteState.segmentSuffix);

    String tempVectorDataMetaFileName = IndexFileNames.segmentFileName(segmentWriteState.segmentInfo.name + "_temp",
        segmentWriteState.segmentSuffix, CuVSVectorsFormat.META_EXTENSION);
    IndexOutput tempVectorIndexMetaFile = segmentWriteState.directory.createOutput(tempVectorDataMetaFileName,
        segmentWriteState.context);
    CodecUtil.writeIndexHeader(tempVectorIndexMetaFile, CuVSVectorsFormat.VECTOR_DATA_CODEC_NAME,
        CuVSVectorsFormat.VERSION_CURRENT, segmentWriteState.segmentInfo.getId(), segmentWriteState.segmentSuffix);

    if (mergeStrategy.equals(MergeStrategy.TRIVIAL_MERGE)) {
      long s = tempVectorIndex.getFilePointer();
      Util.getMergedArchiveCOS(segInputStreams, segmentWriteState.segmentInfo.name,
          new SegmentOutputStream(tempVectorIndex, 100000));
      long e = tempVectorIndex.getFilePointer();
      tempVectorIndexMetaFile.writeInt((int) (e - s));
    } else if (mergeStrategy.equals(MergeStrategy.NON_TRIVIAL_MERGE)) {
      List<float[]> mergedVectors = Util.getMergedVectors(segInputStreams, segmentWriteState.segmentInfo.name);
      Map<String, Integer> metaMap = new LinkedHashMap<String, Integer>();
      long s = tempVectorIndex.getFilePointer();
      ZipOutputStream zos = new ZipOutputStream(new SegmentOutputStream(tempVectorIndex, 100000));
      zos.setLevel(Deflater.NO_COMPRESSION);

      // byte[] bx = jni.initIndex(mergedVectors.get(0).length, mergedVectors, cuvsWriterThreads, intGraphDegree, graphDegree);
      
      byte[] bx = null;
      try {
        bx = createCagraIndex(mergedVectors);
      } catch (Throwable e) {
        e.printStackTrace();
      }

      ZipEntry indexFileZipEntry = new ZipEntry(
          segmentWriteState.segmentInfo.name + "_" + fieldInfo.getName() + ".cag");
      zos.putNextEntry(indexFileZipEntry);
      zos.write(bx, 0, bx.length);
      zos.closeEntry();

      ZipEntry vectorFileZipEntry = new ZipEntry(
          segmentWriteState.segmentInfo.name + "_" + fieldInfo.getName() + ".vec");
      zos.putNextEntry(vectorFileZipEntry);
      new ObjectOutputStream(zos).writeObject(mergedVectors);
      zos.closeEntry();

      metaMap.put(segmentWriteState.segmentInfo.name, mergedVectors.size());
      ZipEntry metaFileZipEntry = new ZipEntry(segmentWriteState.segmentInfo.name + ".meta");
      zos.putNextEntry(metaFileZipEntry);
      new ObjectOutputStream(zos).writeObject(metaMap);
      zos.closeEntry();

      zos.close();
      long e = tempVectorIndex.getFilePointer();
      tempVectorIndexMetaFile.writeInt((int) (e - s));

      mergedVectors.clear();
      metaMap.clear();
    }

    CodecUtil.writeFooter(tempVectorIndex);
    CodecUtil.writeFooter(tempVectorIndexMetaFile);
    IOUtils.close(tempVectorIndex);
    IOUtils.close(tempVectorIndexMetaFile);

    segmentWriteState.directory.deleteFile(vectorDataFileName);
    segmentWriteState.directory.rename(tempVectorDataFileName, vectorDataFileName);

    segmentWriteState.directory.deleteFile(vectorDataMetaFileName);
    segmentWriteState.directory.rename(tempVectorDataMetaFileName, vectorDataMetaFileName);
  }

  public class SegmentOutputStream extends OutputStream {

    IndexOutput out;
    int bufferSize;
    byte[] buffer;
    int p;

    public SegmentOutputStream(IndexOutput out, int bufferSize) throws IOException {
      super();
      this.out = out;
      this.bufferSize = bufferSize;
      this.buffer = new byte[this.bufferSize];
    }

    @Override
    public void write(int b) throws IOException {
      buffer[p] = (byte) b;
      p += 1;
      if (p == bufferSize) {
        flush();
      }
    }

    public void flush() throws IOException {
      out.writeBytes(buffer, p);
      p = 0;
    }

    @Override
    public void close() throws IOException {
      this.flush();
    }

  }
}
