/*
 * SonarScanner Java Library
 * Copyright (C) 2011-2025 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarsource.scanner.lib.internal.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EitherTest {

  @Test
  void testMap() {
    Either<char[], String> either = Either.forLeft(new char[] {'f', 'o', 'o'});
    assertThat(either.isLeft()).isTrue();
    assertThat(either.isRight()).isFalse();
    assertThat((String) either.map(String::new, String::toUpperCase)).isEqualTo("foo");

    either = Either.forRight("bar");
    assertThat(either.isLeft()).isFalse();
    assertThat(either.isRight()).isTrue();
    assertThat((String) either.map(String::new, String::toUpperCase)).isEqualTo("BAR");
  }

}
