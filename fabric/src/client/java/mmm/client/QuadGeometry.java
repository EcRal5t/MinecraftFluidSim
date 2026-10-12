package mmm.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4fc;

/** Shared quad emission primitives for the package-private client geometry builders. */
final class QuadGeometry {
	private QuadGeometry() { }

	static void box(
			Matrix4fc matrix, VertexConsumer vertices,
			float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
			float red, float green, float blue, float alpha
	) {
		quadZ(matrix, vertices, maxZ, minX, maxX, minY, maxY, true, red, green, blue, alpha);
		quadZ(matrix, vertices, minZ, minX, maxX, minY, maxY, false, red, green, blue, alpha);
		quadX(matrix, vertices, minX, minY, maxY, minZ, maxZ, false, red, green, blue, alpha);
		quadX(matrix, vertices, maxX, minY, maxY, minZ, maxZ, true, red, green, blue, alpha);
		quadY(matrix, vertices, maxY, minX, maxX, minZ, maxZ, true, red, green, blue, alpha);
		quadY(matrix, vertices, minY, minX, maxX, minZ, maxZ, false, red, green, blue, alpha);
	}

	static void quadX(
			Matrix4fc matrix, VertexConsumer vertices, float x,
			float minY, float maxY, float minZ, float maxZ, boolean positive,
			float red, float green, float blue, float alpha
	) {
		if (positive) {
			vertex(vertices, matrix, x, minY, maxZ, red, green, blue, alpha);
			vertex(vertices, matrix, x, minY, minZ, red, green, blue, alpha);
			vertex(vertices, matrix, x, maxY, minZ, red, green, blue, alpha);
			vertex(vertices, matrix, x, maxY, maxZ, red, green, blue, alpha);
		} else {
			vertex(vertices, matrix, x, minY, minZ, red, green, blue, alpha);
			vertex(vertices, matrix, x, minY, maxZ, red, green, blue, alpha);
			vertex(vertices, matrix, x, maxY, maxZ, red, green, blue, alpha);
			vertex(vertices, matrix, x, maxY, minZ, red, green, blue, alpha);
		}
	}

	static void quadY(
			Matrix4fc matrix, VertexConsumer vertices, float y,
			float minX, float maxX, float minZ, float maxZ, boolean positive,
			float red, float green, float blue, float alpha
	) {
		if (positive) {
			vertex(vertices, matrix, minX, y, maxZ, red, green, blue, alpha);
			vertex(vertices, matrix, maxX, y, maxZ, red, green, blue, alpha);
			vertex(vertices, matrix, maxX, y, minZ, red, green, blue, alpha);
			vertex(vertices, matrix, minX, y, minZ, red, green, blue, alpha);
		} else {
			vertex(vertices, matrix, minX, y, minZ, red, green, blue, alpha);
			vertex(vertices, matrix, maxX, y, minZ, red, green, blue, alpha);
			vertex(vertices, matrix, maxX, y, maxZ, red, green, blue, alpha);
			vertex(vertices, matrix, minX, y, maxZ, red, green, blue, alpha);
		}
	}

	static void quadZ(
			Matrix4fc matrix, VertexConsumer vertices, float z,
			float minX, float maxX, float minY, float maxY, boolean positive,
			float red, float green, float blue, float alpha
	) {
		if (positive) {
			vertex(vertices, matrix, minX, minY, z, red, green, blue, alpha);
			vertex(vertices, matrix, maxX, minY, z, red, green, blue, alpha);
			vertex(vertices, matrix, maxX, maxY, z, red, green, blue, alpha);
			vertex(vertices, matrix, minX, maxY, z, red, green, blue, alpha);
		} else {
			vertex(vertices, matrix, maxX, minY, z, red, green, blue, alpha);
			vertex(vertices, matrix, minX, minY, z, red, green, blue, alpha);
			vertex(vertices, matrix, minX, maxY, z, red, green, blue, alpha);
			vertex(vertices, matrix, maxX, maxY, z, red, green, blue, alpha);
		}
	}

	static void vertex(
			VertexConsumer consumer, Matrix4fc matrix,
			float x, float y, float z, float red, float green, float blue, float alpha
	) {
		consumer.addVertex(matrix, x, y, z).setColor(red, green, blue, alpha);
	}
}
