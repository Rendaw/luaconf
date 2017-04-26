package com.zarbosoft.luaconf;

import com.google.common.collect.Iterators;
import com.zarbosoft.interface1.Events;
import com.zarbosoft.interface1.Walk;
import com.zarbosoft.interface1.events.*;
import com.zarbosoft.interface1.path.InterfacePath;
import com.zarbosoft.interface1.path.InterfaceRootPath;
import com.zarbosoft.rendaw.common.Pair;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;
import org.reflections.Reflections;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

import static com.zarbosoft.rendaw.common.Common.stream;
import static com.zarbosoft.rendaw.common.Common.uncheck;
import static org.luaj.vm2.LuaValue.*;

public class LuaConf {
	public static <T> T parse(final Reflections reflections, final Class<T> rootClass, final String data) {
		return parse(reflections,
				new Walk.TypeInfo(rootClass),
				"",
				globals -> globals.load(new StringReader(data), "text")
		);
	}

	public static <T> T parse(
			final Reflections reflections, final Type rootClass, final Type rootParameter, final String data
	) {
		return parse(reflections,
				new Walk.TypeInfo(rootClass, rootParameter),
				"",
				globals -> globals.load(new StringReader(data), "text")
		);
	}

	public static <T> T parse(final Reflections reflections, final Class<T> rootClass, final Path path) {
		if (!Files.exists(path))
			throw new InvalidStream(path, "File does not exist.");
		return parse(reflections,
				new Walk.TypeInfo(rootClass),
				path.toString(),
				globals -> globals.loadfile(path.toString())
		);
	}

	public static <T> T parse(
			final Reflections reflections, final Type rootClass, final Type rootParameter, final Path path
	) {
		if (!Files.exists(path))
			throw new InvalidStream(path, "File does not exist.");
		return parse(reflections,
				new Walk.TypeInfo(rootClass, rootParameter),
				path.toString(),
				globals -> globals.loadfile(path.toString())
		);
	}

	public static <T> T parse(
			final Reflections reflections,
			final Walk.TypeInfo typeInfo,
			final String rootPath,
			final Function<Globals, LuaValue> loader
	) {
		return uncheck(() -> {
			final Globals globals = JsePlatform.standardGlobals();
			Walk.walk(reflections, typeInfo, new Walk.DefaultVisitor<Boolean>() {
				@Override
				public Boolean visitAbstract(
						final Field field, final Class<?> klass, final List<Pair<Class<?>, Boolean>> derived
				) {
					derived.stream().forEach(pair -> {
						globals.set(Walk.decideName(pair.first), new OneArgFunction() {
							@Override
							public LuaValue call(final LuaValue luaValue) {
								final LuaTable out = new LuaTable();
								out.set("_type", Walk.decideName(pair.first));
								out.set("_value", luaValue);
								return out;
							}
						});
					});
					return null;
				}
			});
			final LuaValue chunk = loader.apply(globals);
			return Events.parse(reflections,
					typeInfo,
					stream(new TreeIterator(chunk.call(), new InterfaceRootPath(rootPath)))
			);
		});
	}

	private static class TreeIterator implements Iterator<Pair<? extends InterfaceEvent, Object>> {
		private static class Step {
			public final InterfaceEvent event;
			public final Iterator<Step> level;

			private Step(
					final InterfaceEvent event, final Iterator<Step> level
			) {
				this.event = event;
				this.level = level;
			}
		}

		private InterfacePath path;
		private final Deque<Iterator<Step>> stack = new ArrayDeque<>();

		class LeafIterator implements Iterator<Step> {
			private boolean done = false;
			final private LuaValue value;

			LeafIterator(final LuaValue value) {
				this.value = value;
			}

			@Override
			public boolean hasNext() {
				return !done;
			}

			@Override
			public Step next() {
				done = true;
				return process(value);
			}
		}

		class LuaKVIterator implements Iterator<Step> {
			int state = -1;
			LuaValue k = LuaValue.NIL;
			LuaValue v = LuaValue.NIL;
			private final LuaValue value;

			LuaKVIterator(final LuaValue value) {
				this.value = value;
			}

			private void advance() {
				final Varargs n = value.next(k);
				k = n.arg(1);
				if (k.isnil()) {
					v = null;
				} else {
					v = n.arg(2);
				}
				state = 0;
			}

			@Override
			public boolean hasNext() {
				if (state == -1)
					advance();
				return v != null;
			}

			@Override
			public Step next() {
				if (state == -1)
					advance();
				if (v == null)
					throw new NoSuchElementException();
				if (state == 0) {
					state = 1;
					return new Step(new InterfaceKeyEvent(k.tojstring()), null);
				} else {
					state = -1;
					final Step out = process(v);
					advance();
					return out;
				}
			}
		}

		class LuaVIterator implements Iterator<Step> {
			private final LuaValue value;
			int i = 1;

			LuaVIterator(final LuaValue value) {
				this.value = value;
			}

			@Override
			public boolean hasNext() {
				return i - 1 < value.length();
			}

			@Override
			public Step next() {
				return process(value.get(i++));
			}
		}

		public TreeIterator(
				final LuaValue value, final InterfacePath path
		) {
			stack.addLast(new LeafIterator(value));
			this.path = path;
		}

		private Step process(final LuaValue value) {
			switch (value.type()) {
				case TNIL:
					return new Step(new InterfacePrimitiveEvent("null"), null);
				case TBOOLEAN:
					return new Step(new InterfacePrimitiveEvent(value.toboolean() ? "true" : "false"), null);
				case TNUMBER: {
					final double d = value.todouble();
					final String primitive;
					if ((int) d == d)
						primitive = String.format("%s", value.toint());
					else
						primitive = String.format("%s", value.todouble());
					return new Step(new InterfacePrimitiveEvent(primitive), null);
				}
				case TSTRING:
					return new Step(new InterfacePrimitiveEvent(String.format(value.tojstring())), null);
				case TTABLE: {
					final LuaValue type = value.get("_type");
					if (!type.isnil()) {
						return new Step(new InterfaceTypeEvent(type.tojstring()),
								new LeafIterator(value.get("_value"))
						);
					} else if (value.get(1).isnil()) {
						return new Step(new InterfaceObjectOpenEvent(), Iterators.concat(new LuaKVIterator(value),
								Iterators.singletonIterator(new Step(new InterfaceObjectCloseEvent(), null))
						));
					} else {
						return new Step(new InterfaceArrayOpenEvent(), Iterators.concat(new LuaVIterator(value),
								Iterators.singletonIterator(new Step(new InterfaceArrayCloseEvent(), null))
						));
					}
				}
			/*case TFUNCTION:*/
				default:
					throw new InvalidStream(path, String.format("Unknown data type [%s].", TYPE_NAMES[value.type()]));
			}
		}

		@Override
		public boolean hasNext() {
			return !stack.isEmpty() && stack.peekLast().hasNext();
		}

		@Override
		public Pair<InterfaceEvent, Object> next() {
			if (stack.isEmpty())
				throw new NoSuchElementException();
			if (!stack.peekLast().hasNext())
				throw new NoSuchElementException();
			final Iterator<Step> top = stack.peekLast();
			final Step step = top.next();
			if (!top.hasNext())
				stack.removeLast();
			if (step.level != null)
				stack.addLast(step.level);
			final InterfaceEvent event = step.event;
			path = path.push(event);
			return new Pair<>(event, path);
		}
	}
}
