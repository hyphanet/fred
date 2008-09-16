package freenet.support;

import junit.framework.TestCase;

public class UpdatableSortedLinkedListTest extends TestCase {
	private static class T extends UpdatableSortedLinkedListItemImpl {
		private DoublyLinkedList parent;
		private int value;

		public T(int v) {
			this.value = v;
		}

		public DoublyLinkedList getParent() {
			return parent;
		}

		public DoublyLinkedList setParent(DoublyLinkedList l) {
			DoublyLinkedList old = parent;
			parent = l;
			return old;
		}

		public int compareTo(Object o) {
			T t = (T) o;
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
	}

	public void testAdd1() throws UpdatableSortedLinkedListKilledException {
		UpdatableSortedLinkedList l = new UpdatableSortedLinkedList();
		
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
		
		((T) l.getLowest()).assertV(-5);
		((T) l.removeLowest()).assertV(-5);
		assertFalse("isEmpty()", l.isEmpty());
		assertEquals("size()", 10, l.size());
		((T) l.removeLowest()).assertV(-4);
		((T) l.removeLowest()).assertV(-3);
		((T) l.getLowest()).assertV(-2);
		((T) l.removeLowest()).assertV(-2);
		((T) l.removeLowest()).assertV(-1);
		((T) l.removeLowest()).assertV(0);
		((T) l.removeLowest()).assertV(1);
		((T) l.removeLowest()).assertV(2);
		((T) l.removeLowest()).assertV(3);
		((T) l.removeLowest()).assertV(4);
		((T) l.removeLowest()).assertV(5);
		assertTrue("isEmpty()", l.isEmpty());
		assertEquals("size()", 0, l.size());
	}

}
