# Freenet plugin Keep Alive

#### For building run
```gradle jar```

#### Issues
- If there is no block in the network pointed to by uri, then the file cannot be restored at all.
Therefore, when the plug-in first receives such a block, it must be stored.
    ```log
    2019.03.14_18.11_04    *** parsing data structure ***
    2019.03.14_18.11_04    CHK@9FXcd-qio1G16rENmZ-qUFQ8~UyiJb8k9C7iDhKw45M,uwPnJj8-WcPqbhvVggPEa68roQArUB1xBU-jEt-xoj0,AAMC--8
    2019.03.14_18.11_04    -> registered block
    2019.03.14_18.11_04    no metadata
    2019.03.14_18.11_04    *** starting reinsertion ***
    2019.03.14_18.11_04    (0) *** segment size: 1
    2019.03.14_18.11_04    (0) starting reinsertion
    2019.03.14_18.11_04    (0) request: CHK@9FXcd-qio1G16rENmZ-qUFQ8~UyiJb8k9C7iDhKw45M,uwPnJj8-WcPqbhvVggPEa68roQArUB1xBU-jEt-xoj0,AAMC--8 (crypt=3,control=0,compress=-1=none)
    2019.03.14_18.11_04    (0) request: CHK@9FXcd-qio1G16rENmZ-qUFQ8~UyiJb8k9C7iDhKw45M,uwPnJj8-WcPqbhvVggPEa68roQArUB1xBU-jEt-xoj0,AAMC--8 (crypt=3,control=0,compress=-1=none)
    2019.03.14_18.11_04    (0) fetch: CHK@9FXcd-qio1G16rENmZ-qUFQ8~UyiJb8k9C7iDhKw45M,uwPnJj8-WcPqbhvVggPEa68roQArUB1xBU-jEt-xoj0,AAMC--8
    2019.03.14_18.11_04    (0) -> fetch error: The request was terminated by a node because it had recently received a request for the same key and that request had failed
    2019.03.14_18.11_04    (0) insertion: CHK@9FXcd-qio1G16rENmZ-qUFQ8~UyiJb8k9C7iDhKw45M,uwPnJj8-WcPqbhvVggPEa68roQArUB1xBU-jEt-xoj0,AAMC--8
    2019.03.14_18.11_04    (0) -> insertion failed: fetch failed
    2019.03.14_18.11_05    *** reinsertion finished ***
    2019.03.14_18.11_05    *** stopped ***
    ```
- Incomprehensible behavior 
    ```log
    2019.03.15_11.05_04    *** loading list of blocks ***
    2019.03.15_11.05_04    *** continuing reinsertion ***
    2019.03.15_11.05_04    (5) *** segment size: 1
    2019.03.15_11.05_04    (5) starting reinsertion
    2019.03.15_11.05_04    (6) *** segment size: 21
    2019.03.15_11.05_04    (6) starting availability check for segment (n=50)
    2019.03.15_11.05_04    (5) fetch: CHK@9eNS3i7dnctO6e82rLUIH0576N5t3aHP9dQVW-2Vu2s,PGLdzOB-lQVOkmrz1TSxKPHtVrmNHYP9z8hxB~OccNc,AAMC--8
    2019.03.15_11.05_04    (5) -> fetch successful
    ...
    2019.03.15_11.05_13    (6) fetch: CHK@l-BKL9oNqq32ltF8UrmmatjBXC9I2SFVuBTIlYMWPXI,PGLdzOB-lQVOkmrz1TSxKPHtVrmNHYP9z8hxB~OccNc,AAMA--8
    2019.03.15_11.05_13    (6) -> fetch successful
    2019.03.15_11.05_23    (5) insertion: CHK@9eNS3i7dnctO6e82rLUIH0576N5t3aHP9dQVW-2Vu2s,PGLdzOB-lQVOkmrz1TSxKPHtVrmNHYP9z8hxB~OccNc,AAMC--8
    2019.03.15_11.05_23    (5) -> inserted: CHK@9eNS3i7dnctO6e82rLUIH0576N5t3aHP9dQVW-2Vu2s,PGLdzOB-lQVOkmrz1TSxKPHtVrmNHYP9z8hxB~OccNc,AAMA--8
    2019.03.15_11.06_07    (6) fetch: CHK@bkQJXQmN09cCV2V6LuwP34kYjNyeOVPeLJEWJKbezWA,PGLdzOB-lQVOkmrz1TSxKPHtVrmNHYP9z8hxB~OccNc,AAMA--8
    2019.03.15_11.06_07    (6) -> fetch error: Data not found
    2019.03.15_11.06_08    (6) availability of segment ok: 90% (approximated)
    2019.03.15_11.06_08    (6) -> segment not reinserted; moving on will resume on next pass.
    2019.03.15_11.06_08    *** reinsertion finished ***
    2019.03.15_11.06_08    *** stopped ***
    ```
