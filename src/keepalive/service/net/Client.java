package keepalive.service.net;

import freenet.client.*;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.support.io.ArrayBucket;

public class Client {

    private static RequestClient rc = new RequestClient() {

        @Override
        public boolean persistent() {
            return false;
        }

        @Override
        public boolean realTimeFlag() {
            return true;
        }
    };

    // fetch raw data
    public static FetchResult fetch(FreenetURI uri, HighLevelSimpleClientImpl hlsc) throws FetchException {
        uri = normalizeUri(uri);
        assert uri != null;
        if (uri.isCHK()) {
            uri.getExtra()[2] = 0; // deactivate control flag
        }

        FetchContext fetchContext = hlsc.getFetchContext();
        fetchContext.returnZIPManifests = true;
        FetchWaiter fetchWaiter = new FetchWaiter(rc);
        hlsc.fetch(uri, -1, fetchWaiter, fetchContext);
        return fetchWaiter.waitForCompletion();
    }

    public static void insert(FreenetURI uri, byte[] data, HighLevelSimpleClientImpl hlsc) throws InsertException {
        InsertBlock insert = new InsertBlock(new ArrayBucket(data), null, uri);
        hlsc.insert(insert, false, null);
    }

    public static FreenetURI normalizeUri(FreenetURI uri) {
        if (uri.isUSK()) {
            uri = uri.sskForUSK();
        }
        if (uri.hasMetaStrings()) {
            uri = uri.setMetaString(null);
        }
        return uri;
    }
}
