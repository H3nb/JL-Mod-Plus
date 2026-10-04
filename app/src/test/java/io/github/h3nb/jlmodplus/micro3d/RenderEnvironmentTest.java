/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.h3nb.jlmodplus.micro3d;

import static org.junit.Assert.assertEquals;

import com.mascotcapsule.micro3d.v3.Graphics3D;

import org.junit.Test;

public class RenderEnvironmentTest {
	@Test
	public void commandListTextureSelectionRestoresZero() {
		Render render = new Render();
		render.env.textureIdx = 1;

		render.drawCommandList(new int[] {
				Graphics3D.COMMAND_LIST_VERSION_1_0,
				Graphics3D.COMMAND_TEXTURE_INDEX | 0,
				Graphics3D.COMMAND_END
		});

		assertEquals(0, render.env.textureIdx);
	}

	@Test
	public void textureIndexSelectionPreservesLegacyBounds() {
		Render.Environment env = new Render.Environment();

		env.selectTexture(15);
		assertEquals(15, env.textureIdx);

		env.selectTexture(-1);
		assertEquals(15, env.textureIdx);

		env.selectTexture(16);
		assertEquals(15, env.textureIdx);
	}
}
