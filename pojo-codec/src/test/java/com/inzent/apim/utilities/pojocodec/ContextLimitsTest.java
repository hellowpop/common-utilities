package com.inzent.apim.utilities.pojocodec;

import com.inzent.apim.utilities.pojocodec.internal.Session;
import com.inzent.apim.utilities.pojocodec.internal.BudgetedBuffer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ContextLimitsTest {
	@Test
	void depthAndBuffersAreBoundedAndReleased() throws Exception {
		Session session = new Session(new CodecLimits(100,
		                                              10,
		                                              10,
		                                              10,
		                                              10,
		                                              10,
		                                              2,
		                                              15,
		                                              20));
		session.enter(null);
		session.enter(null);
		Assertions.assertThrows(PojoCodecException.class,
		                        () -> session.enter(null));
		session.leave(null);
		session.leave(null);
		try (BudgetedBuffer first = new BudgetedBuffer(session,
		                                               10)) {
			first.write(new byte[10]);
			try (BudgetedBuffer second = new BudgetedBuffer(session,
			                                                10)) {
				Assertions.assertThrows(PojoCodecException.class,
				                        () -> second.write(new byte[6]));
			}
		}
		try (BudgetedBuffer buffer = new BudgetedBuffer(session,
		                                                10)) {
			buffer.write(new byte[10]);
		}
		Assertions.assertThrows(PojoCodecException.class,
		                        () -> session.items(21));
	}

	@Test
	void activePathRejectsCycleButAllowsSharedSiblings() throws Exception {
		Session session = new Session(CodecLimits.defaults());
		Object object = new Object();
		session.enter(object);
		Assertions.assertThrows(PojoCodecException.class,
		                        () -> session.enter(object));
		session.leave(object);
		session.enter(object);
		session.leave(object);
	}
}
