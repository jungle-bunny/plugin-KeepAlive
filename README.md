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
- Availability of segment is ok (persistenceRate >= splitfile_tolerance)
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
- Acceptable availability level for segments (%): 90;
Availability of segment not ok: 76% -->
Segment healing (FEC) successful;
    ```log
    2019.03.18_12.26_36    *** loading list of blocks ***
    2019.03.18_12.26_36    *** starting reinsertion ***
    2019.03.18_12.26_36    (0) *** segment size: 1
    2019.03.18_12.26_36    (0) starting reinsertion
    2019.03.18_12.26_36    (1) *** segment size: 17
    2019.03.18_12.26_36    (1) starting availability check for segment (n=18)
    2019.03.18_12.26_36    (0) request: CHK@TrPXaJThAzMskezRj2kJ869QwGBR6CIKkBu0edKIA7Q,viilidGdHSRY5GlTXrA9RHHPsNzO8gtmsvTn4eHeFzU,AAMC--8 (crypt=3,control=2,compress=-1=none)
    2019.03.18_12.26_36    (1) request: CHK@Or-8HZLw8R8XT4y3cjZDIhDPpHRgJ9Mzk0yRtFupvMY,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8 (crypt=3,control=0,compress=-1=none)
    2019.03.18_12.26_36    (1) request: CHK@3dwfPTl7OYKYBqRtIsDhCoyhIloZHC57Fo2Y53ofzN8,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8 (crypt=3,control=0,compress=-1=none)
    2019.03.18_12.26_36    (0) request: CHK@TrPXaJThAzMskezRj2kJ869QwGBR6CIKkBu0edKIA7Q,viilidGdHSRY5GlTXrA9RHHPsNzO8gtmsvTn4eHeFzU,AAMC--8 (crypt=3,control=2,compress=-1=none)
    2019.03.18_12.26_36    (0) fetch: CHK@TrPXaJThAzMskezRj2kJ869QwGBR6CIKkBu0edKIA7Q,viilidGdHSRY5GlTXrA9RHHPsNzO8gtmsvTn4eHeFzU,AAMC--8
    2019.03.18_12.26_36    (0) -> fetch successful
    2019.03.18_12.26_36    (1) fetch: CHK@3dwfPTl7OYKYBqRtIsDhCoyhIloZHC57Fo2Y53ofzN8,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.26_36    (1) -> fetch successful
    2019.03.18_12.26_36    (1) fetch: CHK@Or-8HZLw8R8XT4y3cjZDIhDPpHRgJ9Mzk0yRtFupvMY,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.26_36    (1) -> fetch successful
    2019.03.18_12.26_37    (1) request: CHK@yMtj9m40PfobYFVI44qBzBbDmH3x5-~ZEOh7xpNcwqE,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8 (crypt=3,control=0,compress=-1=none)
    2019.03.18_12.26_37    (1) request: CHK@bglRl~adJtNyacPZ9436iGlwJilVXbJbnk1~E16ev68,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8 (crypt=3,control=0,compress=-1=none)
    2019.03.18_12.26_37    (1) fetch: CHK@bglRl~adJtNyacPZ9436iGlwJilVXbJbnk1~E16ev68,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.26_37    (1) -> fetch error: The request was terminated by a node because it had recently received a request for the same key and that request had failed
    2019.03.18_12.26_38    (1) request: CHK@qLxq4kHhaIirs73MEfFdAykx4gdq4bmA3lozrNNYoiQ,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8 (crypt=3,control=0,compress=-1=none)
    2019.03.18_12.26_38    (1) fetch: CHK@qLxq4kHhaIirs73MEfFdAykx4gdq4bmA3lozrNNYoiQ,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.26_38    (1) -> fetch error: The request was terminated by a node because it had recently received a request for the same key and that request had failed
    2019.03.18_12.26_39    (1) request: CHK@AVV4eokBeHYQnwpcZeRLp19c-nxlXsGq0q~RxBa43Sw,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8 (crypt=3,control=0,compress=-1=none)
    2019.03.18_12.26_39    (1) fetch: CHK@AVV4eokBeHYQnwpcZeRLp19c-nxlXsGq0q~RxBa43Sw,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.26_39    (1) -> fetch successful
    2019.03.18_12.26_40    (1) request: CHK@8EWZFNTYj0eCwZwODxiLned6~2-l9fhrFwDCl8YExW0,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8 (crypt=3,control=0,compress=-1=none)
    2019.03.18_12.26_40    (1) fetch: CHK@8EWZFNTYj0eCwZwODxiLned6~2-l9fhrFwDCl8YExW0,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.26_40    (1) -> fetch successful
    2019.03.18_12.26_41    (1) fetch: CHK@yMtj9m40PfobYFVI44qBzBbDmH3x5-~ZEOh7xpNcwqE,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.26_41    (1) -> fetch successful
    2019.03.18_12.26_41    (1) request: CHK@vXcph61V7~WOgjsOUW5sOpacSpz1joe-H61gDKhuBEk,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8 (crypt=3,control=0,compress=-1=none)
    2019.03.18_12.26_41    (1) request: CHK@U~A9VwvEP4eSHteif9rGBMhty3tG96-leyreY5tFNSM,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8 (crypt=3,control=0,compress=-1=none)
    2019.03.18_12.26_41    (1) fetch: CHK@U~A9VwvEP4eSHteif9rGBMhty3tG96-leyreY5tFNSM,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.26_41    (1) -> fetch error: The request was terminated by a node because it had recently received a request for the same key and that request had failed
    2019.03.18_12.26_41    (1) fetch: CHK@vXcph61V7~WOgjsOUW5sOpacSpz1joe-H61gDKhuBEk,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.26_41    (1) -> fetch successful
    2019.03.18_12.26_42    (1) availability of segment not ok: 66% (approximated)
    2019.03.18_12.26_42    (1) -> fetch all available blocks now
    2019.03.18_12.26_42    (1) request: CHK@7j8KCL1KVVn--yLj939lNOPB2D0HC-cyintgedi-gzM,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8 (crypt=3,control=0,compress=-1=none)
    2019.03.18_12.26_42    (1) request: CHK@TrfsdA3S4tVHGrW15hP63FwYsD8uFoLSV76CqeJBDYw,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8 (crypt=3,control=0,compress=-1=none)
    2019.03.18_12.26_42    (1) fetch: CHK@7j8KCL1KVVn--yLj939lNOPB2D0HC-cyintgedi-gzM,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.26_42    (1) -> fetch successful
    2019.03.18_12.26_43    (1) request: CHK@nzcVwvCXpsHjnyJhZ7Zo0RNQ~5YcCIh7xW5Epg6psI4,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8 (crypt=3,control=0,compress=-1=none)
    2019.03.18_12.26_44    (1) fetch: CHK@TrfsdA3S4tVHGrW15hP63FwYsD8uFoLSV76CqeJBDYw,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.26_44    (1) -> fetch error: The request was terminated by a node because it had recently received a request for the same key and that request had failed
    2019.03.18_12.26_44    (1) request: CHK@2i9~qhJ3pZAGciuK2Bv~t1MTlgHKvrMfTMB1pwpWDQw,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8 (crypt=3,control=0,compress=-1=none)
    2019.03.18_12.26_44    (1) fetch: CHK@2i9~qhJ3pZAGciuK2Bv~t1MTlgHKvrMfTMB1pwpWDQw,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.26_44    (1) -> fetch successful
    2019.03.18_12.26_45    (1) request: CHK@kAP~Q~pOK2UW4uAC1dxYpAmW0RUeS1utqmnkn8laKGw,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8 (crypt=3,control=0,compress=-1=none)
    2019.03.18_12.26_45    (1) fetch: CHK@kAP~Q~pOK2UW4uAC1dxYpAmW0RUeS1utqmnkn8laKGw,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.26_45    (1) -> fetch successful
    2019.03.18_12.26_46    (1) request: CHK@xbcmqfpIOnKWVC65BZbEIpyp7phe3ZpQIwJ1BUyhJl4,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8 (crypt=3,control=0,compress=-1=none)
    2019.03.18_12.26_46    (1) fetch: CHK@xbcmqfpIOnKWVC65BZbEIpyp7phe3ZpQIwJ1BUyhJl4,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.26_46    (1) -> fetch successful
    2019.03.18_12.26_46    (1) fetch: CHK@nzcVwvCXpsHjnyJhZ7Zo0RNQ~5YcCIh7xW5Epg6psI4,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.26_46    (1) -> fetch successful
    2019.03.18_12.26_47    (1) request: CHK@55OzAUGcqBElH7NhXJ7NOIyDqP9o-OWOK17U2PAPa6Y,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8 (crypt=3,control=0,compress=-1=none)
    2019.03.18_12.26_47    (1) request: CHK@PtBA4z6~eGHymrtGk058uaOp0h7nsaIZoAN8a11XsuI,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8 (crypt=3,control=0,compress=-1=none)
    2019.03.18_12.26_47    (1) fetch: CHK@55OzAUGcqBElH7NhXJ7NOIyDqP9o-OWOK17U2PAPa6Y,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.26_47    (1) -> fetch successful
    2019.03.18_12.26_47    (1) fetch: CHK@PtBA4z6~eGHymrtGk058uaOp0h7nsaIZoAN8a11XsuI,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.26_47    (1) -> fetch successful
    2019.03.18_12.26_48    (1) availability of segment not ok: 76% (exact)
    2019.03.18_12.26_48    (1) starting segment healing
    2019.03.18_12.26_48    (1) start decoding
    2019.03.18_12.26_48    (1)        -> decoding successful
    2019.03.18_12.26_48    (1) start encoding
    2019.03.18_12.26_48    (1)        -> encoding successful
    2019.03.18_12.26_48    (1)        dataBlock_0 = ok
    2019.03.18_12.26_48    (1)        dataBlock_1 = ok
    2019.03.18_12.26_48    (1)        dataBlock_2 = ok
    2019.03.18_12.26_48    (1)        dataBlock_3 = ok
    2019.03.18_12.26_48    (1)        dataBlock_4 = ok
    2019.03.18_12.26_48    (1)        dataBlock_5 = ok
    2019.03.18_12.26_48    (1)        dataBlock_6 = ok
    2019.03.18_12.26_48    (1)        dataBlock_7 = ok
    2019.03.18_12.26_48    (1)        checkBlock_0 = ok
    2019.03.18_12.26_48    (1)        checkBlock_1 = ok
    2019.03.18_12.26_48    (1)        checkBlock_2 = ok
    2019.03.18_12.26_48    (1)        checkBlock_3 = ok
    2019.03.18_12.26_48    (1)        checkBlock_4 = ok
    2019.03.18_12.26_48    (1)        checkBlock_5 = ok
    2019.03.18_12.26_48    (1)        checkBlock_6 = ok
    2019.03.18_12.26_48    (1)        checkBlock_7 = ok
    2019.03.18_12.26_48    (1)        checkBlock_8 = ok
    2019.03.18_12.26_48    (1) segment healing (FEC) successful, start with reinsertion
    2019.03.18_12.26_48    (1) starting reinsertion
    2019.03.18_12.26_48    (1) request: CHK@U~A9VwvEP4eSHteif9rGBMhty3tG96-leyreY5tFNSM,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8 (crypt=3,control=0,compress=-1=none)
    2019.03.18_12.26_48    (1) request: CHK@bglRl~adJtNyacPZ9436iGlwJilVXbJbnk1~E16ev68,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8 (crypt=3,control=0,compress=-1=none)
    2019.03.18_12.27_06    (0) insertion: CHK@TrPXaJThAzMskezRj2kJ869QwGBR6CIKkBu0edKIA7Q,viilidGdHSRY5GlTXrA9RHHPsNzO8gtmsvTn4eHeFzU,AAMC--8
    2019.03.18_12.27_06    (0) -> inserted: CHK@TrPXaJThAzMskezRj2kJ869QwGBR6CIKkBu0edKIA7Q,viilidGdHSRY5GlTXrA9RHHPsNzO8gtmsvTn4eHeFzU,AAMA--8
    2019.03.18_12.27_06    (1) request: CHK@TrfsdA3S4tVHGrW15hP63FwYsD8uFoLSV76CqeJBDYw,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8 (crypt=3,control=0,compress=-1=none)
    2019.03.18_12.27_12    (1) insertion: CHK@bglRl~adJtNyacPZ9436iGlwJilVXbJbnk1~E16ev68,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.27_12    (1) -> inserted: CHK@bglRl~adJtNyacPZ9436iGlwJilVXbJbnk1~E16ev68,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.27_12    (1) request: CHK@qLxq4kHhaIirs73MEfFdAykx4gdq4bmA3lozrNNYoiQ,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8 (crypt=3,control=0,compress=-1=none)
    2019.03.18_12.27_17    (1) insertion: CHK@TrfsdA3S4tVHGrW15hP63FwYsD8uFoLSV76CqeJBDYw,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.27_17    (1) -> inserted: CHK@TrfsdA3S4tVHGrW15hP63FwYsD8uFoLSV76CqeJBDYw,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.27_33    (1) insertion: CHK@qLxq4kHhaIirs73MEfFdAykx4gdq4bmA3lozrNNYoiQ,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.27_33    (1) -> inserted: CHK@qLxq4kHhaIirs73MEfFdAykx4gdq4bmA3lozrNNYoiQ,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.27_37    (1) insertion: CHK@U~A9VwvEP4eSHteif9rGBMhty3tG96-leyreY5tFNSM,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.27_37    (1) -> inserted: CHK@U~A9VwvEP4eSHteif9rGBMhty3tG96-leyreY5tFNSM,Xq54mTTKzghcyNGAq9788lumlq6gwEAIqA9ou5QMwlA,AAMA--8
    2019.03.18_12.27_38    *** reinsertion finished ***
    2019.03.18_12.27_38    *** stopped ***
    ```
