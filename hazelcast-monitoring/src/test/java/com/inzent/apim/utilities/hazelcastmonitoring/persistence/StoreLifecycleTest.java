package com.inzent.apim.utilities.hazelcastmonitoring.persistence;

import jakarta.persistence.*;
import javax.sql.DataSource;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoreLifecycleTest {
    @Test void initializationFailureDestroysOwnedFactoryAndPreservesFailure() {
        var factory=mock(LocalContainerEntityManagerFactoryBean.class);
        var primary=new IllegalStateException("initialization"); var cleanup=new IllegalStateException("destroy");
        doThrow(primary).when(factory).afterPropertiesSet(); doThrow(cleanup).when(factory).destroy();
        assertThatThrownBy(()->new SnapshotStore(mock(DataSource.class),true,factory)).isSameAs(primary).hasSuppressedException(cleanup);
        verify(factory).destroy();
    }
    @Test void missingFactoryObjectAlsoDestroysPartiallyInitializedFactory() {
        var factory=mock(LocalContainerEntityManagerFactoryBean.class);
        assertThatThrownBy(()->new SnapshotStore(mock(DataSource.class),true,factory)).isInstanceOf(IllegalStateException.class);
        verify(factory).destroy();
    }
    @Test void rollbackFailureIsSuppressedAndEntityManagerCloses() {
        var failure=new IllegalArgumentException("body"); var rollback=new IllegalStateException("rollback");
        checkTransaction(failure,rollback,false);
    }
    @Test void fatalBodyFailureAlsoRollsBackAndKeepsOriginalError() {
        checkTransaction(new AssertionError("fatal body"),new IllegalStateException("rollback"),false);
    }
    @Test void transactionStatusFailureDoesNotHideOriginalAndStillCloses() {
        checkTransaction(new IllegalStateException("body"),new IllegalStateException("status"),true);
    }
    private void checkTransaction(Throwable failure,RuntimeException cleanup,boolean statusFails) {
        var factory=mock(LocalContainerEntityManagerFactoryBean.class); var emf=mock(EntityManagerFactory.class);
        var em=mock(EntityManager.class); var tx=mock(EntityTransaction.class);
        when(factory.getObject()).thenReturn(emf); when(emf.createEntityManager()).thenReturn(em);
        when(em.unwrap(Session.class)).thenReturn(mock(Session.class)); when(em.getTransaction()).thenReturn(tx);
        when(em.find(SummaryEntity.class,java.util.UUID.fromString("415e32eb-9bef-482b-a887-b11dc0e2b992"))).thenThrow(failure);
        if(statusFails)when(tx.isActive()).thenThrow(cleanup);
        else { when(tx.isActive()).thenReturn(true); doThrow(cleanup).when(tx).rollback(); }
        try(var store=new SnapshotStore(mock(DataSource.class),true,factory)) {
            assertThatThrownBy(()->store.load(java.util.UUID.fromString("415e32eb-9bef-482b-a887-b11dc0e2b992")))
                .isSameAs(failure).hasSuppressedException(cleanup);
        }
        verify(em).close(); verify(factory).destroy();
        if(!statusFails)verify(tx).rollback();
    }
}
