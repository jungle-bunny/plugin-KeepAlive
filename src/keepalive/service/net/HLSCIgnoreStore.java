package keepalive.service.net;

import freenet.client.FetchContext;
import freenet.client.HighLevelSimpleClientImpl;

public class HLSCIgnoreStore extends HighLevelSimpleClientImpl {

    private static volatile HLSCIgnoreStore hlscIgnoreStore;

    private HLSCIgnoreStore(HighLevelSimpleClientImpl hlsc) {
        super(hlsc);
    }

    static HLSCIgnoreStore getInstance(HighLevelSimpleClientImpl hlsc) {
        HLSCIgnoreStore localHlscIgnoreStore = hlscIgnoreStore;
        if (localHlscIgnoreStore == null) {
            synchronized (HLSCIgnoreStore.class) {
                localHlscIgnoreStore = hlscIgnoreStore;
                if (localHlscIgnoreStore == null) {
                    hlscIgnoreStore = localHlscIgnoreStore = new HLSCIgnoreStore(hlsc);
                }
            }
        }
        return localHlscIgnoreStore;
    }

    @Override
    public FetchContext getFetchContext() {
        FetchContext fc = super.getFetchContext();
        fc.ignoreStore = true;
        return fc;
    }
}
