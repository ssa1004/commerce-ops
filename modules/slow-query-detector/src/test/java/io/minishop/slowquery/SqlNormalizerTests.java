package io.minishop.slowquery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlNormalizerTests {

	@Test
	void numericLiteralsCollapseToPlaceholder() {
		String a = SqlNormalizer.normalize("SELECT * FROM orders WHERE id = 42");
		String b = SqlNormalizer.normalize("SELECT * FROM orders WHERE id = 99");
		assertThat(a).isEqualTo(b);
		assertThat(a).contains("id = ?");
	}

	@Test
	void stringLiteralsCollapseToPlaceholder() {
		String a = SqlNormalizer.normalize("SELECT * FROM users WHERE name = 'Alice'");
		String b = SqlNormalizer.normalize("SELECT * FROM users WHERE name = 'Bob'");
		assertThat(a).isEqualTo(b);
	}

	@Test
	void whitespaceIsCollapsed() {
		String a = SqlNormalizer.normalize("SELECT  *  FROM\torders");
		assertThat(a).isEqualTo("select * from orders");
	}

	@Test
	void trailingSemicolonStripped() {
		assertThat(SqlNormalizer.normalize("SELECT 1;")).isEqualTo("select ?");
	}

	@Test
	void blankInputReturnsEmpty() {
		assertThat(SqlNormalizer.normalize(null)).isEmpty();
		assertThat(SqlNormalizer.normalize("   ")).isEmpty();
	}
}
