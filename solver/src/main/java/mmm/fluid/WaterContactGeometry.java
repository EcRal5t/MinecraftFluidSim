package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import mmm.fluid.GasLiquidContactGeometry.Point;

/** Water-resolved contact rectangles on the committed physical microbox/PLIC geometry. */
final class WaterContactGeometry {
	private WaterContactGeometry() { }

	static List<FluidDomainContracts.WaterContact> capture(HostFluidState domain, List<FluidDomainContracts.WaterContactFace> faces) {
		if (faces == null) throw new IllegalArgumentException("Water contact faces are missing");
		domain.domainFlowFields.preparePhysicalState();
		var phase = domain.pressureSystem.phaseGeometry();
		var areas = new TreeMap<LiquidCellInventory.Key, Double>();
		double h = domain.config.cellSize();
		int[] sizes = {domain.sizeX, domain.sizeY, domain.sizeZ};
		for (var face : new java.util.LinkedHashSet<>(faces)) {
			int axis = face.axis(), a = (axis + 1) % 3, b = (axis + 2) % 3;
			double coordinate = face.coordinateMetres() / h;
			int normalCell = face.side() > 0 ? (int) Math.floor(coordinate) : (int) Math.ceil(coordinate) - 1;
			if (normalCell < 0 || normalCell >= sizes[axis]) continue;
			double local = coordinate - normalCell;
			int normalMicro = face.side() > 0 ? (int) Math.floor(2 * local) : (int) Math.ceil(2 * local) - 1;
			int firstA = Math.max(0, (int) Math.floor(face.minimumAMetres() / h));
			int lastA = Math.min(sizes[a] - 1, (int) Math.ceil(face.maximumAMetres() / h) - 1);
			int firstB = Math.max(0, (int) Math.floor(face.minimumBMetres() / h));
			int lastB = Math.min(sizes[b] - 1, (int) Math.ceil(face.maximumBMetres() / h) - 1);
			for (int ca = firstA; ca <= lastA; ca++) for (int cb = firstB; cb <= lastB; cb++) {
				int[] xyz = new int[3];
				xyz[axis] = normalCell; xyz[a] = ca; xyz[b] = cb;
				int cell = domain.grid.cell(xyz[0],xyz[1],xyz[2]);if(cell<0)continue;
				var row = domain.liquidCells.stocksInCell(cell);
				if (row.isEmpty()) continue;
				var layersBySupport = new java.util.HashMap<Integer, List<GasLiquidContactGeometry.Layer>>();
				for (int micro = 0; micro < 8; micro++) {
					if ((micro >> axis & 1) != normalMicro || (domain.geometry.cutCells.mask(cell) & 1 << micro) != 0) continue;
					var plane = phase.componentPlane(cell, micro);
					if (plane == null || !(plane.fraction() > 0)) continue;
					var layers = layersBySupport.get(plane.support());
					if (layers == null) {
						var stocks = new ArrayList<LiquidCellInventory.Stock>();
						for (var stock : row) if (stock.key().supportMask() == plane.support()) stocks.add(stock);
						boolean water = stocks.stream().anyMatch(stock -> isWater(domain, stock.key().material()));
						layers = water ? GasLiquidContactGeometry.layers(domain, phase, cell, plane.support(), stocks)
								.stream().filter(layer -> isWater(domain, layer.stock().key().material())).toList() : List.of();
						layersBySupport.put(plane.support(), layers);
					}
					if (layers.isEmpty()) continue;
					var polygon = GasLiquidContactGeometry.face(micro, axis, local);
					polygon = GasLiquidContactGeometry.clip(polygon, normal(a, -1), -(face.minimumAMetres() / h - ca));
					polygon = GasLiquidContactGeometry.clip(polygon, normal(a, 1), face.maximumAMetres() / h - ca);
					polygon = GasLiquidContactGeometry.clip(polygon, normal(b, -1), -(face.minimumBMetres() / h - cb));
					polygon = GasLiquidContactGeometry.clip(polygon, normal(b, 1), face.maximumBMetres() / h - cb);
					if (plane.fraction() < 1) polygon = GasLiquidContactGeometry.clip(polygon,
							new Point(plane.nx(), plane.ny(), plane.nz()), plane.offset());
					for (var layer : layers) {
						var selected = polygon;
						if (layer.lower() > 0) selected = GasLiquidContactGeometry.clip(selected, normal(1, -1), -layer.lower());
						if (layer.upper() < 1) selected = GasLiquidContactGeometry.clip(selected, normal(1, 1), layer.upper());
						double area = GasLiquidContactGeometry.area(selected) * h * h * plane.porosity();
						if (area > 0) areas.merge(layer.stock().key(), area, Double::sum);
					}
				}
			}
		}
		var result = new ArrayList<FluidDomainContracts.WaterContact>();
		for (var entry : areas.entrySet()) {
			var key = entry.getKey();
			var stock = domain.liquidCells.stock(key);
			result.add(new FluidDomainContracts.WaterContact(key.cell(), key.supportMask(), key.material(), entry.getValue(),
					domain.unitScale.cubicMeters(stock.volumeFraction())));
		}
		return List.copyOf(result);
	}

	private static Point normal(int axis, double sign) {
		return new Point(axis == 0 ? sign : 0, axis == 1 ? sign : 0, axis == 2 ? sign : 0);
	}

	private static boolean isWater(HostFluidState domain, int material) {
		return domain.materialRegistry == null ? material == 0
				: domain.materialRegistry.material(material).id().equals(BuiltinFluidMaterials.WATER);
	}
}
