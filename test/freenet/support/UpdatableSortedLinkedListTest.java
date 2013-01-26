package freenet.support;

import junit.framework.TestCase;

public class UpdatableSortedLinkedListTest extends TestCase {
	private static class T extends UpdatableSortedLinkedListItemImpl<T> {
		private DoublyLinkedList<? super T> parent;
		private int value;

		public T(int v) {
			this.value = v;
		}

		@Override
		public DoublyLinkedList<? super T> getParent() {
			return parent;
		}

		@Override
		public DoublyLinkedList<? super T> setParent(DoublyLinkedList<? super T> l) {
			DoublyLinkedList<? super T> old = parent;
			parent = l;
			return old;
		}

		@Override
		public int compareTo(T t) {
			return t.value == value ? 0 : t.value > value ? -1 : 1;
		}

		void assertV(int v) {
			assertEquals(v, value);
		}

		@Override
		public boolean equals(Object o) {
			if (o == null)
				return false;
			if (o.getClass() != this.getClass())
				return false;
			T t = (T) o;
			return t.value == value;
		}

		@Override
		public int hashCode() {
			return value;
		}

		@Override
		public String toString() {
			return "(" + value + ")";
		}
	}

	public void testAdd1() throws UpdatableSortedLinkedListKilledException {
		UpdatableSortedLinkedList<T> l = new UpdatableSortedLinkedList<T>();
		l.debug = true;

		assertTrue("isEmpty()", l.isEmpty());
		assertEquals("size()", 0, l.size());
		l.add(new T(2));
		assertFalse("isEmpty()", l.isEmpty());
		l.add(new T(5));
		l.add(new T(-1));
		l.add(new T(-5));
		l.add(new T(3));
		l.add(new T(0));
		l.add(new T(1));
		l.add(new T(-3));
		l.add(new T(-2));
		l.add(new T(4));
		l.add(new T(-4));
		assertEquals("size()", 11, l.size());

		UpdatableSortedLinkedListItem<T>[] a = l.toArray();
		assertEquals(((T)a[0]).value , -5);
		assertEquals(((T)a[1]).value , -4);
		assertEquals(((T)a[2]).value , -3);
		assertEquals(((T)a[3]).value , -2);
		assertEquals(((T)a[4]).value , -1);
		assertEquals(((T)a[5]).value , 0);
		assertEquals(((T)a[6]).value , 1);
		assertEquals(((T)a[7]).value , 2);
		assertEquals(((T)a[8]).value , 3);
		assertEquals(((T)a[9]).value , 4);
		assertEquals(((T)a[10]).value , 5);


		(l.getLowest()).assertV(-5);
		(l.removeLowest()).assertV(-5);
		assertFalse("isEmpty()", l.isEmpty());
		assertEquals("size()", 10, l.size());
		(l.removeLowest()).assertV(-4);
		(l.removeLowest()).assertV(-3);
		(l.getLowest()).assertV(-2);
		(l.removeLowest()).assertV(-2);
		(l.removeLowest()).assertV(-1);
		(l.removeLowest()).assertV(0);
		(l.removeLowest()).assertV(1);
		(l.removeLowest()).assertV(2);
		(l.removeLowest()).assertV(3);
		(l.removeLowest()).assertV(4);
		(l.removeLowest()).assertV(5);
		assertTrue("isEmpty()", l.isEmpty());
		assertEquals("size()", 0, l.size());
	}

	public void testUpdate() throws UpdatableSortedLinkedListKilledException {
		UpdatableSortedLinkedList<T> l = new UpdatableSortedLinkedList<T>();
		l.debug = true;

		T[] t = new T[] { new T(0), new T(1), new T(2), new T(3), new T(4) };

		l.add(t[0]);
		l.add(t[1]);
		l.add(t[2]);
		l.add(t[3]);
		l.add(t[4]);

		t[1].value = -99;
		l.update(t[1]);
		t[0].value = 99;
		l.update(t[0]);
		t[4].value = -98;
		l.update(t[4]);
		t[2].value = 98;
		l.update(t[2]);

		l.update(t[0]);
		l.update(t[1]);
		l.update(t[4]);

		assertSame(t[1], l.removeLowest());
		assertSame(t[4], l.removeLowest());
		assertSame(t[3], l.removeLowest());
		assertSame(t[2], l.removeLowest());
		assertSame(t[0], l.removeLowest());
	}

	public void testClearKill() throws UpdatableSortedLinkedListKilledException {
		UpdatableSortedLinkedList<T> l = new UpdatableSortedLinkedList<T>();
		l.debug = true;

		l.add(new T(2));
		l.add(new T(5));
		l.add(new T(-1));
		l.add(new T(-5));

		l.clear();
		assertEquals(l.size(), 0);

		l.add(new T(3));
		l.add(new T(0));
		l.add(new T(1));
		l.add(new T(-3));
		l.add(new T(-2));
		l.add(new T(4));
		assertEquals(l.size(), 6);

		UpdatableSortedLinkedListItem<T>[] a = l.toArray();
		assertEquals(((T)a[0]).value , -3);
		assertEquals(((T)a[1]).value , -2);
		assertEquals(((T)a[2]).value , 0);
		assertEquals(((T)a[3]).value , 1);
		assertEquals(((T)a[4]).value ,3);
		assertEquals(((T)a[5]).value , 4);

		l.kill();
		assertEquals(l.size(), 0);
		try {
			l.add(new T(-4));
			fail("no UpdatableSortedLinkedListKilledException on add?");
		} catch (UpdatableSortedLinkedListKilledException usllke) {
		}
		try {
			l.remove(new T(-4));
			fail("no UpdatableSortedLinkedListKilledException on remove?");
		} catch (UpdatableSortedLinkedListKilledException usllke) {
		}
		try {
			l.update(new T(-4));
			fail("no UpdatableSortedLinkedListKilledException on update?");
		} catch (UpdatableSortedLinkedListKilledException usllke) {
		}
		try {
			l.toArray();
			fail("no UpdatableSortedLinkedListKilledException on toArray?");
		} catch (UpdatableSortedLinkedListKilledException usllke) {
		}
		try {
			l.removeLowest();	// should it throw?
		} catch (UpdatableSortedLinkedListKilledException usllke) {
			fail("UpdatableSortedLinkedListKilledException on removeLowest?");
		}
	}
}
