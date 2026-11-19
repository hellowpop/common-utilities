package com.inzent.apim.utilities.pojocodec.codec.container;

import com.inzent.apim.utilities.pojocodec.*;
import com.inzent.apim.utilities.pojocodec.internal.*;
import com.inzent.apim.utilities.pojocodec.metadata.GenericTypeResolver;
import com.inzent.apim.utilities.pojocodec.registry.*;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.*;

/**
 * Public-API adapters; never reads JDK implementation fields.
 */
public final class JdkContainers {
	public record Snapshot(ContainerHeader header,
	                       List<Object> items) {
		public Snapshot {
			items = Collections.unmodifiableList(new ArrayList<>(items));
		}
	}

	private final TypeRegistry types;
	private final ComparatorRegistry comparators;
	private final ContainerPolicyRegistry policies;
	private static final Map<Class<?>, String> KINDS = Map.ofEntries(Map.entry(ArrayList.class,
	                                                                           "array-list"),
	                                                                 Map.entry(LinkedList.class,
	                                                                           "linked-list"),
	                                                                 Map.entry(Vector.class,
	                                                                           "vector"),
	                                                                 Map.entry(Stack.class,
	                                                                           "stack"),
	                                                                 Map.entry(HashSet.class,
	                                                                           "hash-set"),
	                                                                 Map.entry(LinkedHashSet.class,
	                                                                           "linked-hash-set"),
	                                                                 Map.entry(TreeSet.class,
	                                                                           "tree-set"),
	                                                                 Map.entry(HashMap.class,
	                                                                           "hash-map"),
	                                                                 Map.entry(LinkedHashMap.class,
	                                                                           "linked-hash-map"),
	                                                                 Map.entry(TreeMap.class,
	                                                                           "tree-map"),
	                                                                 Map.entry(Hashtable.class,
	                                                                           "hashtable"),
	                                                                 Map.entry(IdentityHashMap.class,
	                                                                           "identity-map"),
	                                                                 Map.entry(WeakHashMap.class,
	                                                                           "weak-map"),
	                                                                 Map.entry(Properties.class,
	                                                                           "properties"),
	                                                                 Map.entry(ArrayDeque.class,
	                                                                           "array-deque"),
	                                                                 Map.entry(PriorityQueue.class,
	                                                                           "priority-queue"),
	                                                                 Map.entry(CopyOnWriteArrayList.class,
	                                                                           "copy-list"),
	                                                                 Map.entry(CopyOnWriteArraySet.class,
	                                                                           "copy-set"),
	                                                                 Map.entry(ConcurrentHashMap.class,
	                                                                           "concurrent-map"),
	                                                                 Map.entry(ConcurrentSkipListMap.class,
	                                                                           "concurrent-sorted-map"),
	                                                                 Map.entry(ConcurrentSkipListSet.class,
	                                                                           "concurrent-sorted-set"),
	                                                                 Map.entry(ConcurrentLinkedQueue.class,
	                                                                           "concurrent-queue"),
	                                                                 Map.entry(ConcurrentLinkedDeque.class,
	                                                                           "concurrent-deque"),
	                                                                 Map.entry(LinkedTransferQueue.class,
	                                                                           "transfer-queue"),
	                                                                 Map.entry(ArrayBlockingQueue.class,
	                                                                           "array-blocking-queue"),
	                                                                 Map.entry(LinkedBlockingQueue.class,
	                                                                           "linked-blocking-queue"),
	                                                                 Map.entry(LinkedBlockingDeque.class,
	                                                                           "linked-blocking-deque"),
	                                                                 Map.entry(PriorityBlockingQueue.class,
	                                                                           "priority-blocking-queue"),
	                                                                 Map.entry(DelayQueue.class,
	                                                                           "delay-queue"),
	                                                                 Map.entry(SynchronousQueue.class,
	                                                                           "synchronous-queue"));

	public JdkContainers(TypeRegistry types,
	                     ComparatorRegistry comparators,
	                     ContainerPolicyRegistry policies) {
		this.types = types;
		this.comparators = comparators;
		this.policies = policies;
	}

	private String kind(Object value) throws PojoCodecException {
		String known = KINDS.get(value.getClass());
		if (known != null) {
			return known;
		}
		if (value instanceof EnumSet<?>) {
			return "enum-set";
		}
		if (value instanceof EnumMap<?, ?>) {
			return "enum-map";
		}
		String name = value.getClass()
		                   .getName();
		String base = shape(value);
		if (name.startsWith("java.util.ImmutableCollections$")) {
			return "immutable:" + base;
		}
		if (name.equals("java.util.Arrays$ArrayList")) {
			return "fixed-list";
		}
		if (name.startsWith("java.util.Collections$")) {
			if (name.contains("Synchronized")) {
				return "synchronized:" + base;
			}
			if (name.contains("Checked")) {
				return "checked:" + base;
			}
			if (name.contains("Unmodifiable") || name.contains("Empty") || name.contains("Singleton") || name.endsWith("CopiesList")) {
				return "unmodifiable:" + base;
			}
			if (name.endsWith("SetFromMap")) {
				return "hash-set";
			}
			if (name.endsWith("AsLIFOQueue")) {
				return "linked-list";
			}
			throw new PojoCodecException("Unknown JDK container wrapper: " + name);
		}
		if (name.startsWith("java.util.") && !java.lang.reflect.Modifier.isPublic(value.getClass()
		                                                                               .getModifiers())) {
			return base;
		}
		return "custom:" + this.types.id(value.getClass());
	}

	private static String shape(Object value) {
		if (value instanceof NavigableMap<?, ?>) {
			return "navigable-map";
		}
		if (value instanceof SortedMap<?, ?>) {
			return "sorted-map";
		}
		if (value instanceof Map<?, ?>) {
			return "map";
		}
		if (value instanceof NavigableSet<?>) {
			return "navigable-set";
		}
		if (value instanceof SortedSet<?>) {
			return "sorted-set";
		}
		if (value instanceof Set<?>) {
			return "set";
		}
		if (value instanceof Queue<?>) {
			return "queue";
		}
		if (value instanceof List<?>) {
			return "list";
		}
		return "collection";
	}

	public Snapshot snapshot(Object value,
	                         Type declared,
	                         Session session) throws java.io.IOException {
		String kind = this.kind(value);
		if (kind.startsWith("synchronized:") || value instanceof Properties || value instanceof Hashtable<?, ?> || value instanceof Vector<?>) {
			synchronized (value) {
				return this.capture(value,
				                    declared,
				                    kind,
				                    session);
			}
		}
		return this.capture(value,
		                    declared,
		                    kind,
		                    session);
	}

	private Snapshot capture(Object value,
	                         Type declared,
	                         String kind,
	                         Session session) throws java.io.IOException {
		List<Object> items = new ArrayList<>();
		if (value instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				add(items,
				    new AbstractMap.SimpleImmutableEntry<>(entry.getKey(),
				                                           entry.getValue()),
				    session);
			}
		} else {
			for (Object item : (Collection<?>) value) {
				add(items,
				    item,
				    session);
			}
		}
		Comparator<?> comparator = null;
		if (value instanceof SortedSet<?> sorted) {
			comparator = sorted.comparator();
		}
		if (value instanceof SortedMap<?, ?> sorted) {
			comparator = sorted.comparator();
		}
		if (value instanceof PriorityQueue<?> queue) {
			comparator = queue.comparator();
		}
		if (value instanceof PriorityBlockingQueue<?> queue) {
			comparator = queue.comparator();
		}
		ContainerPolicyRegistry.Policy policy = this.policies.get(value.getClass());
		String enumId = "", checkedKey = "", checkedValue = "";
		if (kind.equals("enum-set") || kind.equals("enum-map")) {
			Class<?> enumeration = policy.enumType();
			if (enumeration == null) {
				Class<?> candidate = GenericTypeResolver.raw(GenericTypeResolver.argument(declared,
				                                                                          0));
				if (candidate.isEnum()) {
					enumeration = candidate;
				}
			}
			if (enumeration == null && !items.isEmpty()) {
				Object sample = value instanceof Map<?, ?>
				                ? ((Map.Entry<?, ?>) items.get(0)).getKey()
				                : items.get(0);
				enumeration = ((Enum<?>) sample).getDeclaringClass();
			}
			if (enumeration == null || !enumeration.isEnum()) {
				throw session.error("Empty enum container needs enum type registration or generic declaration");
			}
			enumId = this.types.declare(enumeration);
		}
		if (kind.startsWith("checked:")) {
			Class<?> key = policy.checkedKeyType() == null
			               ? GenericTypeResolver.raw(GenericTypeResolver.argument(declared,
			                                                                      0))
			               : policy.checkedKeyType();
			Class<?> val = policy.checkedValueType() == null
			               ? GenericTypeResolver.raw(GenericTypeResolver.argument(declared,
			                                                                      1))
			               : policy.checkedValueType();
			if (!knownCheckType(declared,
			                    0,
			                    policy.checkedKeyType()) || (value instanceof Map<?, ?> && !knownCheckType(declared,
			                                                                                               1,
			                                                                                               policy.checkedValueType()))) {
				throw session.error("Checked wrapper needs explicit element/key/value types");
			}
			checkedKey = this.types.declare(key);
			if (value instanceof Map<?, ?>) {
				checkedValue = this.types.declare(val);
			}
		}
		int capacity = 0;
		if (value instanceof ArrayBlockingQueue<?> || value instanceof LinkedBlockingQueue<?> || value instanceof LinkedBlockingDeque<?>) {
			int remaining = ((BlockingQueue<?>) value).remainingCapacity();
			long total = (long) items.size() + remaining;
			if (total > Integer.MAX_VALUE) {
				throw session.error("Concurrent capacity snapshot inconsistent; retry without mutation");
			}
			capacity = (int) total;
		}
		Map<String, String> defaults = new TreeMap<>();
		if (value instanceof Properties properties) {
			for (String key : properties.stringPropertyNames()) {
				if (!(properties.get(key) instanceof String)) {
					defaults.put(key,
					             properties.getProperty(key));
				}
			}
			session.items(defaults.size());
		}
		return new Snapshot(new ContainerHeader(kind,
		                                        this.comparators.id(comparator),
		                                        enumId,
		                                        capacity,
		                                        policy.fair(),
		                                        policy.accessOrder(),
		                                        checkedKey,
		                                        checkedValue,
		                                        defaults),
		                    items);
	}

	private static void add(List<Object> items,
	                        Object item,
	                        Session session) throws PojoCodecException {
		if (items.size() >= session.limits.containerItems()) {
			throw session.error("Container item limit exceeded");
		}
		session.items(1);
		items.add(item);
	}

	private static boolean knownCheckType(Type declared,
	                                      int index,
	                                      Class<?> explicit) {
		if (explicit != null) {
			return true;
		}
		if (!(declared instanceof java.lang.reflect.ParameterizedType)) {
			return false;
		}
		Type argument = GenericTypeResolver.argument(declared,
		                                             index);
		return argument instanceof Class<?> || argument instanceof java.lang.reflect.ParameterizedType || argument instanceof java.lang.reflect.GenericArrayType;
	}

	@SuppressWarnings({"rawtypes",
	                   "unchecked"})
	public Object restore(ContainerHeader header,
	                      List<Object> items,
	                      Type declared,
	                      Session session) throws java.io.IOException {
		return this.restore(header,
		                    items,
		                    declared,
		                    session,
		                    null);
	}

	public Object createInjectable(ContainerHeader header,
	                               Type declared,
	                               Session session) throws java.io.IOException {
		if (!header.kind()
		           .startsWith("custom:")) {
			return null;
		}
		Class<?> type = this.types.resolve(header.kind()
		                                         .substring("custom:".length()));
		if (!PojoInjectable.class.isAssignableFrom(type)) {
			return null;
		}
		if ((!Collection.class.isAssignableFrom(type) && !Map.class.isAssignableFrom(type)) || !GenericTypeResolver.raw(declared)
		                                                                                                           .isAssignableFrom(type)) {
			throw session.error("Invalid injectable container type");
		}
		try {
			return type.getConstructor()
			           .newInstance();
		}
		catch (ReflectiveOperationException | RuntimeException exception) {
			throw session.error("Cannot create injectable container",
			                    exception);
		}
	}

	@SuppressWarnings({"rawtypes",
	                   "unchecked"})
	public Object restore(ContainerHeader header,
	                      List<Object> items,
	                      Type declared,
	                      Session session,
	                      Object existing) throws java.io.IOException {
		try {
			Comparator comparator = this.comparators.resolve(header.comparator());
			String kind = header.kind();
			String base = kind.contains(":")
			              ? kind.substring(kind.indexOf(':') + 1)
			              : kind;
			boolean wrapper = kind.startsWith("immutable:") || kind.startsWith("unmodifiable:") || kind.startsWith("checked:") || kind.startsWith("synchronized:");
			Object container;
			if (kind.startsWith("custom:")) {
				Class<?> type = this.types.resolve(base);
				if (!Collection.class.isAssignableFrom(type) && !Map.class.isAssignableFrom(type)) {
					throw session.error("Invalid custom container type");
				}
				if (existing != null && !type.isInstance(existing)) {
					throw session.error("Wrong existing container type");
				}
				container = existing == null
				            ? type.getConstructor()
				                  .newInstance()
				            : existing;
			} else {
				container = switch (base) {
					case "list",
					     "collection",
					     "array-list",
					     "fixed-list" -> new ArrayList<>();
					case "linked-list" -> new LinkedList<>();
					case "vector" -> new Vector<>();
					case "stack" -> new Stack<>();
					case "set",
					     "linked-hash-set" -> new LinkedHashSet<>();
					case "hash-set" -> new HashSet<>();
					case "sorted-set",
					     "navigable-set",
					     "tree-set" -> new TreeSet<>(comparator);
					case "map",
					     "linked-hash-map" -> new LinkedHashMap<>(16,
					                                              0.75f,
					                                              header.accessOrder());
					case "hash-map" -> new HashMap<>();
					case "sorted-map",
					     "navigable-map",
					     "tree-map" -> new TreeMap<>(comparator);
					case "hashtable" -> new Hashtable<>();
					case "identity-map" -> new IdentityHashMap<>();
					case "weak-map" -> new WeakHashMap<>();
					case "properties" -> {
						Properties defaults = new Properties();
						defaults.putAll(header.defaults());
						yield new Properties(defaults);
					}
					case "queue" -> new LinkedList<>();
					case "array-deque" -> new ArrayDeque<>();
					case "priority-queue" -> new PriorityQueue<>(Math.max(1,
					                                                      items.size()),
					                                             comparator);
					case "enum-set" -> {
						Class<?> type = this.types.resolve(header.enumType());
						if (!type.isEnum()) {
							throw session.error("Expected enum type");
						}
						yield EnumSet.noneOf((Class) type);
					}
					case "enum-map" -> {
						Class<?> type = this.types.resolve(header.enumType());
						if (!type.isEnum()) {
							throw session.error("Expected enum type");
						}
						yield new EnumMap((Class) type);
					}
					case "copy-list" -> new CopyOnWriteArrayList<>();
					case "copy-set" -> new CopyOnWriteArraySet<>();
					case "concurrent-map" -> new ConcurrentHashMap<>();
					case "concurrent-sorted-map" -> new ConcurrentSkipListMap<>(comparator);
					case "concurrent-sorted-set" -> new ConcurrentSkipListSet<>(comparator);
					case "concurrent-queue" -> new ConcurrentLinkedQueue<>();
					case "concurrent-deque" -> new ConcurrentLinkedDeque<>();
					case "transfer-queue" -> new LinkedTransferQueue<>();
					case "array-blocking-queue" -> new ArrayBlockingQueue<>(capacity(header,
					                                                                 items,
					                                                                 session),
					                                                        header.fair());
					case "linked-blocking-queue" -> new LinkedBlockingQueue<>(capacity(header,
					                                                                   items,
					                                                                   session));
					case "linked-blocking-deque" -> new LinkedBlockingDeque<>(capacity(header,
					                                                                   items,
					                                                                   session));
					case "priority-blocking-queue" -> new PriorityBlockingQueue<>(Math.max(1,
					                                                                       items.size()),
					                                                              comparator);
					case "delay-queue" -> new DelayQueue<>();
					case "synchronous-queue" -> new SynchronousQueue<>(header.fair());
					default -> throw session.error("Unknown container kind: " + kind);
				};
			}
			if (kind.equals("synchronous-queue") && !items.isEmpty()) {
				throw session.error("SynchronousQueue cannot contain items");
			}
			if (container instanceof Map map) {
				for (Object item : items) {
					Map.Entry<?, ?> entry = (Map.Entry<?, ?>) item;
					if (map.containsKey(entry.getKey())) {
						throw session.error("Duplicate Map key after decoding");
					}
					map.put(entry.getKey(),
					        entry.getValue());
				}
			} else {
				Collection collection = (Collection) container;
				for (Object item : items) {
					boolean added = collection instanceof Queue queue
					                ? queue.offer(item)
					                : collection.add(item);
					if (!added) {
						throw session.error("Duplicate Set item or rejected Queue item");
					}
				}
			}
			if (kind.equals("fixed-list")) {
				container = Arrays.asList(((List) container).toArray());
			}
			if (wrapper) {
				container = this.wrap(kind,
				                      container,
				                      header);
			}
			Class<?> target = GenericTypeResolver.raw(declared);
			if (!target.isInstance(container)) {
				throw session.error("Container " + kind + " is not assignable to " + target.getName());
			}
			return container;
		}
		catch (PojoCodecException exception) {
			throw exception;
		}
		catch (ReflectiveOperationException | RuntimeException exception) {
			throw session.error("Cannot restore container " + header.kind(),
			                    exception);
		}
	}

	private static int capacity(ContainerHeader header,
	                            List<Object> items,
	                            Session session) throws PojoCodecException {
		if (header.capacity() <= 0 || header.capacity() < items.size()) {
			throw session.error("Invalid bounded capacity");
		}
		if (header.kind()
		          .equals("array-blocking-queue") && header.capacity() > session.limits.containerItems()) {
			throw session.error("ArrayBlockingQueue capacity exceeds allocation limit");
		}
		return header.capacity();
	}

	@SuppressWarnings({"rawtypes",
	                   "unchecked"})
	private Object wrap(String kind,
	                    Object container,
	                    ContainerHeader header) throws PojoCodecException {
		String mode = kind.substring(0,
		                             kind.indexOf(':'));
		if (mode.equals("immutable")) {
			if (container instanceof Map map) {
				return Map.copyOf(map);
			}
			if (container instanceof Set set) {
				return Set.copyOf(set);
			}
			return List.copyOf((Collection) container);
		}
		if (mode.equals("unmodifiable")) {
			if (container instanceof NavigableMap map) {
				return Collections.unmodifiableNavigableMap(map);
			}
			if (container instanceof SortedMap map) {
				return Collections.unmodifiableSortedMap(map);
			}
			if (container instanceof Map map) {
				return Collections.unmodifiableMap(map);
			}
			if (container instanceof NavigableSet set) {
				return Collections.unmodifiableNavigableSet(set);
			}
			if (container instanceof SortedSet set) {
				return Collections.unmodifiableSortedSet(set);
			}
			if (container instanceof Set set) {
				return Collections.unmodifiableSet(set);
			}
			if (container instanceof List list) {
				return Collections.unmodifiableList(list);
			}
			return Collections.unmodifiableCollection((Collection) container);
		}
		if (mode.equals("synchronized")) {
			if (container instanceof NavigableMap map) {
				return Collections.synchronizedNavigableMap(map);
			}
			if (container instanceof SortedMap map) {
				return Collections.synchronizedSortedMap(map);
			}
			if (container instanceof Map map) {
				return Collections.synchronizedMap(map);
			}
			if (container instanceof NavigableSet set) {
				return Collections.synchronizedNavigableSet(set);
			}
			if (container instanceof SortedSet set) {
				return Collections.synchronizedSortedSet(set);
			}
			if (container instanceof Set set) {
				return Collections.synchronizedSet(set);
			}
			if (container instanceof List list) {
				return Collections.synchronizedList(list);
			}
			return Collections.synchronizedCollection((Collection) container);
		}
		Class key = this.types.resolve(header.checkedKey());
		if (key.isPrimitive()) {
			throw new PojoCodecException("Checked type must be a reference type");
		}
		if (container instanceof Map map) {
			Class value = this.types.resolve(header.checkedValue());
			for (Object item : map.entrySet()) {
				Map.Entry entry = (Map.Entry) item;
				check(key,
				      entry.getKey());
				check(value,
				      entry.getValue());
			}
			if (map instanceof NavigableMap sorted) {
				return Collections.checkedNavigableMap(sorted,
				                                       key,
				                                       value);
			}
			if (map instanceof SortedMap sorted) {
				return Collections.checkedSortedMap(sorted,
				                                    key,
				                                    value);
			}
			return Collections.checkedMap(map,
			                              key,
			                              value);
		}
		for (Object item : (Collection) container) {
			check(key,
			      item);
		}
		if (container instanceof NavigableSet sorted) {
			return Collections.checkedNavigableSet(sorted,
			                                       key);
		}
		if (container instanceof SortedSet sorted) {
			return Collections.checkedSortedSet(sorted,
			                                    key);
		}
		if (container instanceof Set set) {
			return Collections.checkedSet(set,
			                              key);
		}
		if (kind.endsWith(":queue")) {
			return Collections.checkedQueue((Queue) container,
			                                key);
		}
		if (container instanceof List list) {
			return Collections.checkedList(list,
			                               key);
		}
		if (container instanceof Queue queue) {
			return Collections.checkedQueue(queue,
			                                key);
		}
		return Collections.checkedCollection((Collection) container,
		                                     key);
	}

	private static void check(Class<?> type,
	                          Object value) throws PojoCodecException {
		if (value != null && !type.isInstance(value)) {
			throw new PojoCodecException("Checked container element type mismatch");
		}
	}
}
