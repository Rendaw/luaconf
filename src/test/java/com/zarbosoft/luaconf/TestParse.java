package com.zarbosoft.luaconf;

import com.google.common.collect.ImmutableList;
import com.zarbosoft.interface1.Configuration;
import com.zarbosoft.interface1.Walk;
import org.junit.Test;
import org.reflections.Reflections;

import java.util.Arrays;
import java.util.List;

import static org.unitils.reflectionassert.ReflectionAssert.assertReflectionEquals;

public class TestParse {

	@Configuration
	public static class AllRoot {
		public AllRoot() {
		}

		@Configuration
		public int a;

		@Configuration
		public String b;

		@Configuration
		public List<Integer> c;
	}

	@Test
	public void testAll() {
		final AllRoot got = LuaConf.parse(
				new Reflections("com.zarbosoft.luaconf"),
				new Walk.TypeInfo(AllRoot.class),
				"return {a = 1, b = 'string', c = {1, 2, 3}};"
		);
		final AllRoot expected = new AllRoot();
		expected.a = 1;
		expected.b = "string";
		expected.c = Arrays.asList(1, 2, 3);
		assertReflectionEquals(expected, got);
	}

	public static class Types {
		@Configuration
		public abstract static class Base {

		}

		@Configuration(name = "a")
		public static class A extends Base {

		}

		@Configuration(name = "b")
		public static class B extends Base {

		}
	}

	@Test
	public void testTypes() {
		final List<Types.Base> got = LuaConf.parse(
				new Reflections("com.zarbosoft.luaconf"),
				new Walk.TypeInfo(List.class, new Walk.TypeInfo(Types.Base.class)),
				"return {a {}, b {}};"
		);
		final List<Types.Base> expected = ImmutableList.of(new Types.A(), new Types.B());
		assertReflectionEquals(expected, got);
	}
}
