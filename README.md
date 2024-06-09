# Lucene CuVS Integration

This is an integration for [CuVS](https://github.com/rapidsai/cuvs), GPU accelerated vector search library from NVIDIA (formerly part of [Raft](https://github.com/rapidsai/raft)), into [Apache Lucene](https://github.com/apache/lucene).

## Architecture

As an initial integration, the CuVS library is plugged in as a new KnnVectorFormat via a custom codec.

![Architecture](lucene-cuvs-architecture.png "Lucene CuVS Architecture")

By way of a working example, Wikipedia corpus (1.3M documents) can be indexed, each document having a content vector. Queries (questions.vec.txt) can be executed after the indexing.

> :warning: This is not production ready yet.

## Running

Install NVIDIA drivers, CUDA and JDK 21.

Set the correct path for Raft in `cuda/CMakeLists.txt` file. Then, proceed to run the following (Wikipedia OpenAI benchmark):

    # 1.3 Million wikipedia documents with vector embeddings, along with some query embeddings
    wget -c https://accounts.searchscale.com/wikipedia_vector_dump.csv.gz
    wget -c https://accounts.searchscale.com/questions.vec.txt
    wget -c https://accounts.searchscale.com/questions.raw.txt

    java -jar cuvs-searcher-lucene-0.0.1-SNAPSHOT-jar-with-dependencies.jar <dump_file> <vector_column_number> <vector_column_name> <number_of_documents_to_index> <vector_dimension> <query_file> <commit_at_number_of_documents> <topK> <no. of HNSW indexing threads> <no. of cuvs indexing threads> <merge_strategy options: NO_MERGE | TRIVIAL_MERGE | NON_TRIVIAL_MERGE> <queryThreads> <hnswMaxConn> <hnswBeamWidth> <hnswVisitedLimit> <cagraIntermediateGraphDegree> <cagraGraphDegree> <cagraITopK> <cagraSearchWidth>

    Example:
    java -Xmx32G -jar cuvs-searcher-lucene-0.0.1-SNAPSHOT-jar-with-dependencies.jar wikipedia_vector_dump.csv.gz 3 article_vector 12000000 768 query.txt 300000 10 32 32 NO_MERGE 1 16 100 10 128 64 5 1


## Contributors

* Vivek Narang, SearchScale
* Ishan Chattopadhyaya, SearchScale & Committer, Apache Lucene & Solr
* Corey Nolet, NVIDIA
* Puneet Ahuja, SearchScale
* Kishore Angani, SearchScale
* Noble Paul, SearchScale & Committer, Apache Lucene & Solr
