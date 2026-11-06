package com.inzent.apim.utilities.hazelcastmonitoring;

import com.inzent.apim.utilities.hazelcastmonitoring.snapshot.Snapshot;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SnapshotContractTest {
    @Test void nodeRequiredFieldsAreRejectedBeforePersistenceOrExport() {
        assertThatThrownBy(()->new Snapshot.Node(null,"name","address","OBSERVED_ACTIVE",false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Snapshot.Node(UUID.randomUUID(),"name","address",null,false)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void objectStatusAndCountAreValidatedAtConstruction() {
        assertThatThrownBy(()->new Snapshot.ObjectInfo("MAP","map","orders",null,null,null,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Snapshot.ObjectInfo("MAP","map","orders",null,Snapshot.CountStatus.SUCCESS,null,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Snapshot.ObjectInfo("MAP","map","orders",0L,Snapshot.CountStatus.ERROR,null,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Snapshot.ObjectInfo("MAP","map","orders",null,Snapshot.CountStatus.NOT_COLLECTED,null,null,null).objectCount()).isNull();
    }
}
