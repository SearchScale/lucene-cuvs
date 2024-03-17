# Lucene CuVS Integration

This is an integration for [CuVS](https://github.com/rapidsai/cuvs), GPU accelerated vector search library from NVIDIA (formerly part of [Raft](https://github.com/rapidsai/raft)), into [Apache Lucene](https://github.com/apache/lucene).

## Architecture

As an initial integration, the CuVS library is plugged in as an IndexSearcher. This project has two layers: (1) Java/JNI layer in `lucene` dir, (2) CuVS/C++ layer in `cuda` dir.

By way of a working example, OpenAI's Wikipedia corpus (25k documents) can be indexed, each document having a content vector. A provided sample query (query.txt) can be executed after the indexing.

> :warning: This is not production ready yet.

## Running

Install RAFT (https://docs.rapids.ai/api/raft/stable/build/#installation)

Set the correct path for Raft in `cuda/CMakeLists.txt` file. Then, proceed to run the following (Wikipedia OpenAI benchmark):

    wget -c https://cdn.openai.com/API/examples/data/vector_database_wikipedia_articles_embedded.zip
    mvn package
    java -jar lucene/target/cuvs-searcher-lucene-0.0.1-SNAPSHOT-jar-with-dependencies.jar

## Benchmarks

Wikipedia (OpenAI, 1536 dimensions, 25k vectors):

|     | CuVS (RTX 4090) | Lucene HNSW (Ryzen 7950X) | Improvement |
| -------- | ------- | ------------------------- | ------------- |
| Indexing  | 5.2 seconds    | 26.5 seconds | 5.1x |
| Searching | 1 millisecond     | 22 milliseconds | 22x |

Wikipedia (768 dimensions, 750k vectors):

|     | CuVS (RTX 4090) | Lucene HNSW (Ryzen 7950X) | Improvement |
| -------- | ------- | ------------------------- | ------- |
| Indexing  | 167.9 seconds    | 804 seconds | 4.8x |


> :warning: Switching over the index building algorithm from IVF_PQ to [NN_DESCENT](https://github.com/rapidsai/raft/pull/1748) will likely result in further 8x (or better) speed up. This is work in progress.

## Next steps
* Instead of using the IVF_PQ build algorithm of Cagra, switch over to NN_DESCENT, for a further 8x (or better) improvement in indexing speed.
* Instead of extending the IndexSearcher, create a [KnnVectorFormat](https://github.com/apache/lucene/blob/main/lucene/core/src/java/org/apache/lucene/codecs/KnnVectorsFormat.java) and corresponding KnnVectorsWriter and KnnVectorsReader for tighter integration.

## Contributors

* Vivek Narang, SearchScale
* Ishan Chattopadhyaya, SearchScale & Committer, Apache Lucene & Solr
* Kishore Angani, SearchScale
* Noble Paul, SearchScale & Committer, Apache Lucene & Solr
