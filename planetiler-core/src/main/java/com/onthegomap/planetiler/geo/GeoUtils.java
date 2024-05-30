package com.onthegomap.planetiler.geo;

import com.onthegomap.planetiler.collection.LongLongMap;
import com.onthegomap.planetiler.config.PlanetilerConfig;
import com.onthegomap.planetiler.stats.Stats;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.locationtech.jts.algorithm.Area;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.TopologyException;
import org.locationtech.jts.geom.impl.PackedCoordinateSequence;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;
import org.locationtech.jts.geom.util.GeometryFixer;
import org.locationtech.jts.geom.util.GeometryTransformer;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.precision.GeometryPrecisionReducer;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;


/**
 * A collection of utilities for working with JTS data structures and geographic data.
 * <p>
 * "world" coordinates in this class refer to EPSG 3031 coordinates.
 */
public class GeoUtils {

  /** Rounding precision for 256x256px tiles encoded using 4096 values. */
  public static final PrecisionModel TILE_PRECISION = new PrecisionModel(1000d);  // Adjust as needed for EPSG 3031
  public static final GeometryFactory JTS_FACTORY = new GeometryFactory(PackedCoordinateSequenceFactory.DOUBLE_FACTORY);

  public static final Geometry EMPTY_GEOMETRY = JTS_FACTORY.createGeometryCollection();
  public static final CoordinateSequence EMPTY_COORDINATE_SEQUENCE = new PackedCoordinateSequence.Double(0, 2, 0);
  public static final Point EMPTY_POINT = JTS_FACTORY.createPoint();
  public static final LineString EMPTY_LINE = JTS_FACTORY.createLineString();
  public static final Polygon EMPTY_POLYGON = JTS_FACTORY.createPolygon();
  private static final LineString[] EMPTY_LINE_STRING_ARRAY = new LineString[0];
  private static final Polygon[] EMPTY_POLYGON_ARRAY = new Polygon[0];
  private static final Point[] EMPTY_POINT_ARRAY = new Point[0];
  private static final double WORLD_RADIUS_METERS = 6_378_137;
  public static final double WORLD_CIRCUMFERENCE_METERS = Math.PI * 2 * WORLD_RADIUS_METERS;
  private static final double LOG2 = Math.log(2);
  

  private static final CRSFactory crsFactory = new CRSFactory();
  private static final CoordinateReferenceSystem epsg3031 = crsFactory.createFromName("EPSG:3031");
  private static final CoordinateReferenceSystem wgs84 = crsFactory.createFromName("EPSG:4326");
  private static final CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();
  private static final CoordinateTransform transformToWGS84 = ctFactory.createTransform(epsg3031, wgs84);
  private static final CoordinateTransform transformToEPSG3031 = ctFactory.createTransform(wgs84, epsg3031);

  /**
   * Transform EPSG 3031 coordinates to latitude/longitude coordinates.
   */
  private static final GeometryTransformer UNPROJECT_WORLD_COORDS = new GeometryTransformer() {
    @Override
    protected CoordinateSequence transformCoordinates(CoordinateSequence coords, Geometry parent) {
      CoordinateSequence copy = new PackedCoordinateSequence.Double(coords.size(), 2, 0);
      for (int i = 0; i < coords.size(); i++) {
        ProjCoordinate srcCoord = new ProjCoordinate(coords.getX(i), coords.getY(i));
        ProjCoordinate destCoord = new ProjCoordinate();
        transformToWGS84.transform(srcCoord, destCoord);
        copy.setOrdinate(i, 0, destCoord.x);
        copy.setOrdinate(i, 1, destCoord.y);
      }
      return copy;
    }
  };

  /**
   * Transform latitude/longitude coordinates to EPSG 3031.
   */
  private static final GeometryTransformer PROJECT_WORLD_COORDS = new GeometryTransformer() {
    @Override
    protected CoordinateSequence transformCoordinates(CoordinateSequence coords, Geometry parent) {
      CoordinateSequence copy = new PackedCoordinateSequence.Double(coords.size(), 2, 0);
      for (int i = 0; i < coords.size(); i++) {
        ProjCoordinate srcCoord = new ProjCoordinate(coords.getX(i), coords.getY(i));
        ProjCoordinate destCoord = new ProjCoordinate();
        transformToEPSG3031.transform(srcCoord, destCoord);
        copy.setOrdinate(i, 0, destCoord.x);
        copy.setOrdinate(i, 1, destCoord.y);
      }
      return copy;
    }
  };
    
  private static final double MAX_LAT = getWorldLat(-0.1);
  private static final double MIN_LAT = getWorldLat(1.1);
  // to pack latitude/longitude into a single long, we round them to 31 bits of precision
  private static final double QUANTIZED_WORLD_SIZE = Math.pow(2, 31);
  private static final double HALF_QUANTIZED_WORLD_SIZE = QUANTIZED_WORLD_SIZE / 2;
  private static final long LOWER_32_BIT_MASK = (1L << 32) - 1L;
  /**
   * Bounds for the entire area of the planet that a web mercator projection covers, where top left is (0,0) and bottom
   * right is (1,1).
   */
  public static final Envelope WORLD_BOUNDS = new Envelope(0, 1, 0, 1);
  /**
   * Bounds for the entire area of the planet that a web mercator projection covers in latitude/longitude coordinates.
   */
  public static final Envelope WORLD_LAT_LON_BOUNDS = toLatLonBoundsBounds(WORLD_BOUNDS);

  // should not instantiate
  private GeoUtils() {}

  /**
   * Returns a copy of {@code geom} transformed from latitude/longitude coordinates to EPSG 3031.
   */
  public static Geometry latLonToWorldCoords(Geometry geom) {
    return PROJECT_WORLD_COORDS.transform(geom);
  }

  /**
   * Returns a copy of {@code geom} transformed from EPSG 3031 to latitude/longitude coordinates.
   */
  public static Geometry worldToLatLonCoords(Geometry geom) {
    return UNPROJECT_WORLD_COORDS.transform(geom);
  }

  /**
   * Returns a copy of {@code worldBounds} transformed from EPSG 3031 coordinates to latitude/longitude.
   */
  public static Envelope toLatLonBoundsBounds(Envelope worldBounds) {
    ProjCoordinate min = new ProjCoordinate(worldBounds.getMinX(), worldBounds.getMinY());
    ProjCoordinate max = new ProjCoordinate(worldBounds.getMaxX(), worldBounds.getMaxY());
    ProjCoordinate minLatLon = new ProjCoordinate();
    ProjCoordinate maxLatLon = new ProjCoordinate();
    transformToWGS84.transform(min, minLatLon);
    transformToWGS84.transform(max, maxLatLon);
    return new Envelope(minLatLon.x, maxLatLon.x, minLatLon.y, maxLatLon.y);
  }

  /**
   * Returns a copy of {@code lonLatBounds} transformed from latitude/longitude coordinates to EPSG 3031.
   */
  public static Envelope toWorldBounds(Envelope lonLatBounds) {
    ProjCoordinate min = new ProjCoordinate(lonLatBounds.getMinX(), lonLatBounds.getMinY());
    ProjCoordinate max = new ProjCoordinate(lonLatBounds.getMaxX(), lonLatBounds.getMaxY());
    ProjCoordinate minWorld = new ProjCoordinate();
    ProjCoordinate maxWorld = new ProjCoordinate();
    transformToEPSG3031.transform(min, minWorld);
    transformToEPSG3031.transform(max, maxWorld);
    return new Envelope(minWorld.x, maxWorld.x, minWorld.y, maxWorld.y);
  }

  /**
   * Returns the longitude for an EPSG 3031 coordinate {@code x}.
   */
  public static double getWorldLon(double x) {
    ProjCoordinate srcCoord = new ProjCoordinate(x, 0);
    ProjCoordinate destCoord = new ProjCoordinate();
    transformToWGS84.transform(srcCoord, destCoord);
    return destCoord.x;
  }

  /**
   * Returns the latitude for an EPSG 3031 coordinate {@code y}.
   */
  public static double getWorldLat(double y) {
    ProjCoordinate srcCoord = new ProjCoordinate(0, y);
    ProjCoordinate destCoord = new ProjCoordinate();
    transformToWGS84.transform(srcCoord, destCoord);
    return destCoord.y;
  }

  /**
   * Returns the EPSG 3031 X coordinate for {@code longitude}.
   */
  public static double getWorldX(double longitude) {
    ProjCoordinate srcCoord = new ProjCoordinate(longitude, 0);
    ProjCoordinate destCoord = new ProjCoordinate();
    transformToEPSG3031.transform(srcCoord, destCoord);
    return destCoord.x;
  }

  /**
   * Returns the EPSG 3031 Y coordinate for {@code latitude}.
   */
  public static double getWorldY(double latitude) {
    ProjCoordinate srcCoord = new ProjCoordinate(0, latitude);
    ProjCoordinate destCoord = new ProjCoordinate();
    transformToEPSG3031.transform(srcCoord, destCoord);
    return destCoord.y;
  }

  /**
   * Returns a latitude/longitude coordinate encoded into a single 64-bit long for storage in a {@link LongLongMap} that
   * can be decoded using {@link #decodeWorldX(long)} and {@link #decodeWorldY(long)}.
   */
  public static long encodeFlatLocation(double lon, double lat) {
    double worldX = getWorldX(lon);
    double worldY = getWorldY(lat);
    long x = (long) (worldX * HALF_QUANTIZED_WORLD_SIZE);
    long y = (long) (worldY * HALF_QUANTIZED_WORLD_SIZE);
    return (x << 32) | (y & LOWER_32_BIT_MASK);
  }

  /**
   * Returns the EPSG 3031 Y coordinate of the latitude/longitude encoded with {@link #encodeFlatLocation(double, double)}.
   */
  public static double decodeWorldY(long encoded) {
    return ((encoded & LOWER_32_BIT_MASK) / HALF_QUANTIZED_WORLD_SIZE);
  }

  /**
   * Returns the EPSG 3031 X coordinate of the latitude/longitude encoded with {@link #encodeFlatLocation(double, double)}.
   */
  public static double decodeWorldX(long encoded) {
    return ((encoded >>> 32) / HALF_QUANTIZED_WORLD_SIZE);
  }

  /**
   * Returns an approximate zoom level that a map should be displayed at to show all of {@code envelope}, specified in
   * latitude/longitude coordinates.
   */
  public static double getZoomFromLonLatBounds(Envelope envelope) {
    Envelope worldBounds = GeoUtils.toWorldBounds(envelope);
    return getZoomFromWorldBounds(worldBounds);
  }

  /**
   * Returns an approximate zoom level that a map should be displayed at to show all of {@code envelope}, specified in
   * EPSG 3031 coordinates.
   */
  public static double getZoomFromWorldBounds(Envelope worldBounds) {
    double maxEdge = Math.max(worldBounds.getWidth(), worldBounds.getHeight());
    return Math.max(0, -Math.log(maxEdge) / Math.log(2));
  }

  /** Returns the width in meters of a single pixel of a 256x256 px tile at the given {@code zoom} level. */
  public static double metersPerPixelAtEquator(int zoom) {
    // This might need adjustment based on EPSG 3031 specifics; below is a generic calculation
    return WORLD_CIRCUMFERENCE_METERS / Math.pow(2d, zoom + 8d);
  }

  /** Returns the length in pixels for a given number of meters on a 256x256 px tile at the given {@code zoom} level. */
  public static double metersToPixelAtEquator(int zoom, double meters) {
    return meters / metersPerPixelAtEquator(zoom);
  }

  public static Point point(double x, double y) {
    return JTS_FACTORY.createPoint(new CoordinateXY(x, y));
  }

  public static Point point(Coordinate coord) {
    return JTS_FACTORY.createPoint(coord);
  }

  public static MultiLineString createMultiLineString(List<LineString> lineStrings) {
    return JTS_FACTORY.createMultiLineString(lineStrings.toArray(EMPTY_LINE_STRING_ARRAY));
  }

  public static MultiPolygon createMultiPolygon(List<Polygon> polygon) {
    return JTS_FACTORY.createMultiPolygon(polygon.toArray(EMPTY_POLYGON_ARRAY));
  }

  /**
   * Attempt to fix any self-intersections or overlaps in {@code geom}.
   *
   * @throws GeometryException if a robustness error occurred
   */
  public static Geometry fixPolygon(Geometry geom) throws GeometryException {
    try {
      return geom.buffer(0);
    } catch (TopologyException e) {
      throw new GeometryException("fix_polygon_topology_error", "robustness error fixing polygon: " + e);
    }
  }

  /**
   * More aggressive fix for self-intersections than {@link #fixPolygon(Geometry)} that expands then contracts the shape
   * by {@code buffer}.
   *
   * @throws GeometryException if a robustness error occurred
   */
  public static Geometry fixPolygon(Geometry geom, double buffer) throws GeometryException {
    try {
      return geom.buffer(buffer).buffer(-buffer);
    } catch (TopologyException e) {
      throw new GeometryException("fix_polygon_buffer_topology_error", "robustness error fixing polygon: " + e);
    }
  }

  public static Geometry combineLineStrings(List<LineString> lineStrings) {
    return lineStrings.size() == 1 ? lineStrings.get(0) : createMultiLineString(lineStrings);
  }
  
  public static Geometry combinePolygons(List<Polygon> polys) {
    return polys.size() == 1 ? polys.get(0) : createMultiPolygon(polys);
  }
  
  public static Geometry combinePoints(List<Point> points) {
    return points.size() == 1 ? points.get(0) : createMultiPoint(points);
  }
  
  /**
   * Returns a copy of {@code geom} with coordinates rounded to {@link #TILE_PRECISION} and fixes any polygon
   * self-intersections or overlaps that may have caused.
   */
  public static Geometry snapAndFixPolygon(Geometry geom, Stats stats, String stage) throws GeometryException {
    return snapAndFixPolygon(geom, TILE_PRECISION, stats, stage);
  }

  /**
   * Returns a copy of {@code geom} with coordinates rounded to {@code #tilePrecision} and fixes any polygon
   * self-intersections or overlaps that may have caused.
   *
   * @throws GeometryException if an unrecoverable robustness exception prevents us from fixing the geometry
   */
  public static Geometry snapAndFixPolygon(Geometry geom, PrecisionModel tilePrecision, Stats stats, String stage)
      throws GeometryException {
    try {
      if (!geom.isValid()) {
        geom = fixPolygon(geom);
        stats.dataError(stage + "_snap_fix_input");
      }
      return GeometryPrecisionReducer.reduce(geom, tilePrecision);
    } catch (TopologyException | IllegalArgumentException e) {
      // precision reduction fails if geometry is invalid, so attempt
      // to fix it then try again
      geom = GeometryFixer.fix(geom);
      stats.dataError(stage + "_snap_fix_input2");
      try {
        return GeometryPrecisionReducer.reduce(geom, tilePrecision);
      } catch (TopologyException | IllegalArgumentException e2) {
        // give it one last try but with more aggressive fixing, just in case (see issue #511)
        geom = fixPolygon(geom, tilePrecision.getScale() / 2);
        stats.dataError(stage + "_snap_fix_input3");
        try {
          return GeometryPrecisionReducer.reduce(geom, tilePrecision);
        } catch (TopologyException | IllegalArgumentException e3) {
          stats.dataError(stage + "_snap_fix_input3_failed");
          throw new GeometryException("snap_third_time_failed", "Error reducing precision");
        }
      }
    }
  }

  private static double wrapDouble(double value, double max) {
    value %= max;
    if (value < 0) {
      value += max;
    }
    return value;
  }

  private static long longPair(int a, int b) {
    return (((long) a) << 32L) | (b & LOWER_32_BIT_MASK);
  }

  /**
   * Breaks the world up into a grid and returns an ID for the square that {@code coord} falls into.
   *
   * @param tilesAtZoom       the tile width of the world at this zoom level
   * @param labelGridTileSize the tile width of each grid square
   * @param coord             the coordinate, scaled to this zoom level
   * @return an ID representing the grid square that {@code coord} falls into.
   */
  public static long labelGridId(int tilesAtZoom, double labelGridTileSize, Coordinate coord) {
    return GeoUtils.longPair(
      (int) Math.floor(wrapDouble(coord.getX(), tilesAtZoom) / labelGridTileSize),
      (int) Math.floor((coord.getY()) / labelGridTileSize)
    );
  }

  /** Returns a {@link CoordinateSequence} from a list of {@code x, y, x, y, ...} coordinates. */
  public static CoordinateSequence coordinateSequence(double... coords) {
    return new PackedCoordinateSequence.Double(coords, 2, 0);
  }

  public static MultiPoint createMultiPoint(List<Point> points) {
    return JTS_FACTORY.createMultiPoint(points.toArray(EMPTY_POINT_ARRAY));
  }

  /**
   * Returns line strings for every inner and outer ring contained in a polygon.
   *
   * @throws GeometryException if {@code geom} contains anything other than polygons or line strings
   */
  public static Geometry polygonToLineString(Geometry geom) throws GeometryException {
    List<LineString> lineStrings = new ArrayList<>();
    getLineStrings(geom, lineStrings);
    if (lineStrings.isEmpty()) {
      throw new GeometryException("polygon_to_linestring_empty", "No line strings");
    } else if (lineStrings.size() == 1) {
      return lineStrings.get(0);
    } else {
      return createMultiLineString(lineStrings);
    }
  }

  private static void getLineStrings(Geometry input, List<LineString> output) throws GeometryException {
    switch (input.getGeometryType()) {
      case "LinearRing" -> {
        LinearRing linearRing = (LinearRing) input;
        output.add(JTS_FACTORY.createLineString(linearRing.getCoordinateSequence()));
      }
      case "LineString" -> output.add((LineString) input);
      case "Polygon" -> {
        Polygon polygon = (Polygon) input;
        getLineStrings(polygon.getExteriorRing(), output);
        for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
          getLineStrings(polygon.getInteriorRingN(i), output);
        }
      }
      case "GeometryCollection" -> {
        GeometryCollection gc = (GeometryCollection) input;
        for (int i = 0; i < gc.getNumGeometries(); i++) {
          getLineStrings(gc.getGeometryN(i), output);
        }
      }
      default -> throw new GeometryException("get_line_strings_bad_type",
          "unrecognized geometry type: " + (input == null ? "null" : input.getGeometryType()));
    }
  }

  public static Geometry createGeometryCollection(List<Geometry> polygonGroup) {
    return JTS_FACTORY.createGeometryCollection(polygonGroup.toArray(Geometry[]::new));
  }

  /**
   * Returns a point approximately {@code ratio} of the way from start to end and {@code offset} units to the right.
   */
  public static Point pointAlongOffset(LineString lineString, double ratio, double offset) {
    int numPoints = lineString.getNumPoints();
    int middle = (int) Math.max(0, Math.min(numPoints * ratio, numPoints - 2));
    Coordinate a = lineString.getCoordinateN(middle);
    Coordinate b = lineString.getCoordinateN(middle + 1);
    LineSegment segment = new LineSegment(a, b);
    return JTS_FACTORY.createPoint(segment.pointAlongOffset(0.5, offset));
  }

  public static Polygon createPolygon(LinearRing exteriorRing, List<LinearRing> rings) {
    return JTS_FACTORY.createPolygon(exteriorRing, rings.toArray(LinearRing[]::new));
  }

  /**
   * Returns {@code true} if the signed area of the triangle formed by 3 sequential points changes sign anywhere along
   * {@code ring}, ignoring repeated and collinear points.
   */
  public static boolean isConvex(LinearRing ring) {
    double threshold = 1e-3;
    double minPointsToCheck = 10;
    CoordinateSequence seq = ring.getCoordinateSequence();
    int size = seq.size();
    if (size <= 3) {
      return false;
    }

    // ignore leading repeated points
    double c0x = seq.getX(0);
    double c0y = seq.getY(0);
    double c1x = Double.NaN, c1y = Double.NaN;
    int i;
    for (i = 1; i < size; i++) {
      c1x = seq.getX(i);
      c1y = seq.getY(i);
      if (c1x != c0x || c1y != c0y) {
        break;
      }
    }

    double dx1 = c1x - c0x;
    double dy1 = c1y - c0y;

    double negZ = 1e-20, posZ = 1e-20;

    // need to wrap around to make sure the triangle formed by last and first points does not change sign
    for (; i <= size + 1; i++) {
      // first and last point should be the same, so skip index 0
      int idx = i < size ? i : (i + 1 - size);
      double c2x = seq.getX(idx);
      double c2y = seq.getY(idx);

      double dx2 = c2x - c1x;
      double dy2 = c2y - c1y;
      double z = dx1 * dy2 - dy1 * dx2;

      double absZ = Math.abs(z);

      // look for sign changes in the triangles formed by sequential points
      // but, we want to allow for rounding errors and small concavities relative to the overall shape
      // so track the largest positive and negative threshold for triangle area and compare them once we
      // have enough points
      boolean extendedBounds = false;
      if (z < 0 && absZ > negZ) {
        negZ = absZ;
        extendedBounds = true;
      } else if (z > 0 && absZ > posZ) {
        posZ = absZ;
        extendedBounds = true;
      }

      if (i == minPointsToCheck || (i > minPointsToCheck && extendedBounds)) {
        double ratio = negZ < posZ ? negZ / posZ : posZ / negZ;
        if (ratio > threshold) {
          return false;
        }
      }

      c1x = c2x;
      c1y = c2y;
      dx1 = dx2;
      dy1 = dy2;
    }
    return (negZ < posZ ? negZ / posZ : posZ / negZ) < threshold;
  }

  /**
   * If {@code geometry} is a {@link MultiPolygon}, returns a copy with polygons sorted by descending area of the outer
   * shell, otherwise returns the input geometry.
   */
  public static Geometry sortPolygonsByAreaDescending(Geometry geometry) {
    if (geometry instanceof MultiPolygon multiPolygon) {
      PolyAndArea[] areas = new PolyAndArea[multiPolygon.getNumGeometries()];
      for (int i = 0; i < multiPolygon.getNumGeometries(); i++) {
        areas[i] = new PolyAndArea((Polygon) multiPolygon.getGeometryN(i));
      }
      return JTS_FACTORY.createMultiPolygon(
        Stream.of(areas).sorted().map(d -> d.poly).toArray(Polygon[]::new)
      );
    } else {
      return geometry;
    }
  }

  /** Combines multiple geometries into one {@link GeometryCollection}. */
  public static Geometry combine(Geometry... geometries) {
    List<Geometry> innerGeometries = new ArrayList<>();
    // attempt to flatten out nested geometry collections
    for (var geom : geometries) {
      if (geom instanceof GeometryCollection collection) {
        for (int i = 0; i < collection.getNumGeometries(); i++) {
          innerGeometries.add(collection.getGeometryN(i));
        }
      } else {
        innerGeometries.add(geom);
      }
    }
    return innerGeometries.size() == 1 ? innerGeometries.getFirst() :
      JTS_FACTORY.createGeometryCollection(innerGeometries.toArray(Geometry[]::new));
  }

  /**
   * For a feature of size {@code worldGeometrySize} (where 1=full planet), determine the minimum zoom level at which
   * the feature appears at least {@code minPixelSize} pixels large.
   * <p>
   * The result will be clamped to the range [0, {@link PlanetilerConfig#MAX_MAXZOOM}].
   */
  public static int minZoomForPixelSize(double worldGeometrySize, double minPixelSize) {
    double worldPixels = worldGeometrySize * 256;
    return Math.clamp((int) Math.ceil(Math.log(minPixelSize / worldPixels) / LOG2), 0,
      PlanetilerConfig.MAX_MAXZOOM);
  }

  public static WKBReader wkbReader() {
    return new WKBReader(JTS_FACTORY);
  }

  public static WKTReader wktReader() {
    return new WKTReader(JTS_FACTORY);
  }

  /** Helper class to sort polygons by area of their outer shell. */
  private record PolyAndArea(Polygon poly, double area) implements Comparable<PolyAndArea> {

    PolyAndArea(Polygon poly) {
      this(poly, Area.ofRing(poly.getExteriorRing().getCoordinateSequence()));
    }

    @Override
    public int compareTo(PolyAndArea o) {
      return -Double.compare(area, o.area);
    }
  }
}
