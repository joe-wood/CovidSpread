import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.BufferedWriter;
import java.io.FileWriter;
public class CovidReader
{
  /**
   * These are all the classes needed to read the various JSON
   */
  static public class PropertiesJson
  {
    String GEOID;
    String STATEFP;
  }

  static public class GeometryJson
  {
    String type;
    PropertiesJson properties;
    int[][][] arcs;
  }

  static public class CountiesJson
  {
    String type;
    GeometryJson[] geometries;
  }

  static public class ObjectsJson
  {
    CountiesJson counties20m;
  }

  static public class TransformJson
  {
    double[] scale;
    double[] translate;
  }

  static public class TopologyJson
  {
    String type;
    int[][][] arcs;
    ObjectsJson objects;
    TransformJson transform;
  }

  static public class SJson
  {
    String N;
    int T;
  }

  static public class DM0Json
  {
    String[] C;
    SJson[] S;
    int R = -1;
    int Ø = -1;
  }

  static public class PHJson
  {
    DM0Json[] DM0;
  }

  static public class DSJson
  {
    boolean HAD;
    boolean IC;
    String N;
    PHJson[] PH;
  }

  static public class DsrJson
  {
    int Version;
    int MinorVersion;
    DSJson[] DS;
  }

  static public class GroupKeyJson
  {
    String Calc;
    boolean IsSameAsSelect;
    SourceJson Source;
  }

  static public class SelectJson
  {
    int Kind;
    int Depth;
    String Name;
    String Value;
    String Format;
    GroupKeyJson[] GroupKeys;
  }

  static public class TopJson
  {
    int Count;
  }

  static public class LimitsPrimaryJson
  {
    String Id;
    TopJson Top;
  }

  static public class LimitsJson
  {
    LimitsPrimaryJson Primary;
  }

  static public class SourceJson
  {
    String Entity;
    String Property;
  }

  static public class KeysJson
  {
    int Select;
    SourceJson Source;
  }

  static public class GroupingsJson
  {
    KeysJson[] Keys;
    String Member;
  }

  static public class PrimaryJson
  {
    GroupingsJson[] Groupings;
  }

  static public class ExpressionsJson
  {
    PrimaryJson Primary;
  }

  static public class DescriptorJson
  {
    ExpressionsJson Expressions;
    LimitsJson Limits;
    SelectJson[] Select;
    int Version;
  }

  static public class QueryDataJson
  {
    DescriptorJson descriptor;
    DsrJson dsr;
  }

  static public class ResultDataJson
  {
    QueryDataJson data;
  }

  static public class JobDataJson
  {
    String jobId;
    ResultDataJson result;
  }

  static public class ResultsDataJson
  {
    String jobId;
    ResultDataJson result;
  }

  static public class JobsDataJson
  {
    String[] jobIds;
    ResultsDataJson[] results;
  }

  /**
   * Topology data
   */
  static private TopologyJson s_topologyJson;

  /**
   * Used to translate county name -> ID -> topology
   */
  static private JobsDataJson s_jobsDataJson;

  /**
   * Track map dimensions
   */
  static private int s_maxX = 0;
  static private int s_maxY = 0;

  /**
   * Simple 2D transform values (scale, translate, etc)
   */
  static public class Transform
  {
    private double _x;
    private double _y;

    public double getX() { return _x; }
    public double getY() { return _y; }

    public Transform(double pX,
    double pY)
    {
      _x = pX;
      _y = pY;
    }
  }

  /**
   * Shockingly, a... point
   */
  static public class Point
  {
    private int _x;
    private int _y;

    public int getX() { return _x; }
    public int getY() { return _y; }

    public Point(int pX,
                 int pY)
    {
      _x = pX;
      _y = pY;

      if (_x > s_maxX) s_maxX = _x;
      if (_y > s_maxY) s_maxY = _y;
    }

    public Point(int[] pPoint)
    {
      this(pPoint[0], pPoint[1]);
    }

    public Point(Point pPoint)
    {
      this(pPoint.getX(), pPoint.getY());
    }

    public Point(Point pPoint, PointDelta pDelta)
    {
      this(pPoint.getX() + pDelta.getDeltaX(), pPoint.getY() + pDelta.getDeltaY());
    }

    public String toString()
    {
      return String.format("(%5d, %5d)", getX(), getY());
    }

    public int hashCode()
    {
      return getX() + getY();
    }

    public boolean equals(Object pOther)
    {
      if (!(pOther instanceof Point)) return false;
      Point point = (Point) pOther;
      return (point.getX() == getX()) && (point.getY() == getY());
    }
  }

  /**
   * Used with topology arc data, which specify point and then deltas from the previous location
   */
  static public class PointDelta
  {
    private int _deltaX;
    private int _deltaY;

    public int getDeltaX() { return _deltaX; }
    public int getDeltaY() { return _deltaY; }

    public PointDelta(int[] pDelta)
    {
      _deltaX = pDelta[0];
      _deltaY = pDelta[1];
    }

    public String toString()
    {
      return String.format("[%5d, %5d]", getDeltaX(), getDeltaY());
    }
  }

  /**
   * Outline of a county
   */
  static public class CountyPolygon
  {
    private GeometryJson _geometryJson;
    public GeometryJson getGeometryJson() { return _geometryJson; }

    private int _countyId;
    public int getCountyId() { return _countyId; }

    private int _stateId;
    public int getStateId() { return _stateId; }

    private List<Arc> _arcs;
    public List<Arc> getArcs() { return _arcs; }

    public CountyPolygon(GeometryJson pGeometryJson)
    {
      _geometryJson = pGeometryJson;
      _countyId = Integer.valueOf(pGeometryJson.properties.GEOID);
      _stateId = Integer.valueOf(pGeometryJson.properties.STATEFP);
      _arcs = new ArrayList<>();

      for (int loop = 0; loop < pGeometryJson.arcs.length; ++loop)
      {
        int[][] arcs = pGeometryJson.arcs[loop];
        for (int loopInner = 0; loopInner < arcs.length; ++loopInner)
        {
          List<Point> points = new ArrayList<>();

          for (int pieces = 0; pieces < arcs[loopInner].length; ++pieces)
          {
            int piece = arcs[loopInner][pieces];

            // Here's a fun bit: if the piece is negative, that means that you want to reverse
            // the indicated arc, *but* (and this threw me for a while), you need to subtract 1
            // from the resulting absolute value; this is so you can reverse arc #0 if need be
            // (you'd specify -1 -> |-1| = 1 -> 1-1 = 0; likewise -53 -> |-53| = 53 -> 53 - 1 = 52,
            // so '-53' means reverse arc 52)

            Arc arc = (piece < 0) ? s_arcList.get((int) Math.abs(piece) - 1) : s_arcList.get(piece);

            Arc temp = new Arc(arc, piece < 0);
            for (Point point : temp.getPoints())
            {
              if ((points.size() == 0) || !points.get(points.size()-1).equals(point))
              {
                points.add(point);
              }
            }
          }
          getArcs().add(new Arc(points));
        }
      }
    }

    public String toString()
    {
      StringBuilder builder = new StringBuilder();
      for (Arc arc : getArcs())
      {
        builder.append(arc.toString()).append("-");
      }
      return builder.toString();
    }
  }

  // Uses starting point and delta to get list of points.
  static public class Arc
  {
    private Point _start;
    private List<PointDelta> _deltas;
    private List<Point> _points;

    public Point getStart() { return _start; }
    public List<PointDelta> getDeltas() { return _deltas; }
    public List<Point> getPoints() { return _points; }

    public Arc(Arc pArc, boolean pReverse)
    {
      _start = new Point(pReverse ? pArc.getPoints().get(pArc.getPoints().size() - 1) : pArc.getPoints().get(0));

      _points = new ArrayList<>();

      if (pReverse)
      {
        for (Point point : pArc.getPoints())
        {
          _points.add(0, point);
        }
      }
      else
      {
        for (Point point : pArc.getPoints())
        {
          _points.add(point);
        }
      }
    }

    public Arc(List<Point> pPoints)
    {
      if (pPoints.isEmpty()) return;
      _start = pPoints.get(0);

      _points = new ArrayList<>();

      for (Point point : pPoints)
      {
        _points.add(point);
      }
    }

    public Arc(int[][] pArcs)
    {
      _start = new Point(pArcs[0]);

      _deltas = new ArrayList<>();
      for (int delta = 1; delta < pArcs.length; ++delta)
      {
        _deltas.add(new PointDelta(pArcs[delta]));
      }

      _points = new ArrayList<>();

      Point currentPoint = new Point(getStart());
      _points.add(currentPoint);

      for (PointDelta delta : getDeltas())
      {
        currentPoint = new Point(currentPoint, delta);
        _points.add(currentPoint);
      }
    }

    public String toString()
    {
      StringBuilder builder = new StringBuilder();

      if (getPoints() != null)
      {
        for (Point point : getPoints())
        {
          builder.append(point.toString()).append(" ");
        }
      }

      return builder.toString();
    }
  }

  /**
   * County info; since we're loading historical data separately, *most* of this is
   * ignored
   */
  static public class CountyInfo
  {
    private int _rValue;
    private int _countyId;
    private String _county;
    private double _casesPer100K = -1.0;
    private double _casesDaily7dayRoll = -1.0;
    private int _totalCases = -1;
    private int _totalDeaths = -1;
    private String _color = "";

    public int getRValue() { return _rValue; }
    public int getCountyId() { return _countyId; }
    public String getCounty() { return _county; }
    public String getColor() { return _color; }
    public double getCasesPer100K() { return _casesPer100K; }
    public double getCasesDaily7dayRoll() { return _casesDaily7dayRoll; }
    public int getTotalCases() { return _totalCases; }
    public int getTotalDeaths() { return _totalDeaths; }

    public CountyInfo(DM0Json pDM0)
    {
      _rValue = pDM0.R;
      _countyId = Integer.valueOf(pDM0.C[0]);
      _county = pDM0.C[1];

      // The 'Ø' and 'R' values alter how we interpret the stack of values
      if (pDM0.Ø >= 0)
      {
        _color = pDM0.C[2];
        _totalCases = Integer.valueOf(pDM0.C[3]);
        _totalDeaths = Integer.valueOf(pDM0.C[4]);
        _casesPer100K = Double.valueOf(pDM0.C[5]);
      }
      else if (_rValue == -1) // i.e., no R value was specified
      {
        _color = pDM0.C[2];
        _casesPer100K = Double.valueOf(pDM0.C[3]);
        _totalCases = Integer.valueOf(pDM0.C[4]);
        _totalDeaths = Integer.valueOf(pDM0.C[5]);
        _casesDaily7dayRoll = Double.valueOf(pDM0.C[6]);
      }
      else if (_rValue == 4)
      {
        _casesPer100K = Double.valueOf(pDM0.C[2]);
        _totalCases = Integer.valueOf(pDM0.C[3]);
        _totalDeaths = Integer.valueOf(pDM0.C[4]);
        _casesDaily7dayRoll = Double.valueOf(pDM0.C[5]);
      }
      else if (_rValue == 16)
      {
        _color = pDM0.C[2];
        _casesPer100K = Double.valueOf(pDM0.C[3]);
        _totalCases = Integer.valueOf(pDM0.C[4]);
        _casesDaily7dayRoll = Double.valueOf(pDM0.C[5]);
      }
      else if (_rValue == 20)
      {
        _casesPer100K = Double.valueOf(pDM0.C[2]);
        _totalCases = Integer.valueOf(pDM0.C[3]);
        _casesDaily7dayRoll = Double.valueOf(pDM0.C[4]);
      }
      else if (_rValue == 32)
      {
        _color = pDM0.C[2];
        _casesPer100K = Double.valueOf(pDM0.C[3]);
        _totalCases = Integer.valueOf(pDM0.C[4]);
        _casesDaily7dayRoll = Double.valueOf(pDM0.C[5]);
      }
      else if (_rValue == 36)
      {
        _casesPer100K = Double.valueOf(pDM0.C[2]);
        _totalCases = Integer.valueOf(pDM0.C[3]);
        _casesDaily7dayRoll = Double.valueOf(pDM0.C[4]);
      }
      else if (_rValue == 48)
      {
        _color = pDM0.C[2];
        _casesPer100K = Double.valueOf(pDM0.C[3]);
        _totalCases = Integer.valueOf(pDM0.C[4]);
      }
      else if (_rValue == 52)
      {
        _casesPer100K = Double.valueOf(pDM0.C[2]);
        _casesDaily7dayRoll = Double.valueOf(pDM0.C[3]);
      }
      else if (_rValue == 64)
      {
        _color = pDM0.C[2];
        _casesPer100K = Double.valueOf(pDM0.C[3]);
        _totalCases = Integer.valueOf(pDM0.C[4]);
        _casesDaily7dayRoll = Double.valueOf(pDM0.C[5]);
      }
      else if (_rValue == 68)
      {
        _casesPer100K = Double.valueOf(pDM0.C[2]);
        _totalCases = Integer.valueOf(pDM0.C[3]);
        _totalDeaths = Integer.valueOf(pDM0.C[4]);
      }
      else if (_rValue == 76)
      {
        _totalCases = Integer.valueOf(pDM0.C[2]);
        _totalDeaths = Integer.valueOf(pDM0.C[3]);
      }
      else if (_rValue == 108)
      {
        _totalCases = Integer.valueOf(pDM0.C[2]);
      }
      else
      {
        System.out.println("R: " + _rValue);
      }
    }

    public String toString()
    {
      return String.format("[%2d] ID: %6d | Name: %-30s | Per100K: %12.4f | Daily: %10.4f | Total: %6d | Dead: %5d | %6s |",
        getRValue(),
        getCountyId(),
        getCounty(),
        getCasesPer100K(),
        getCasesDaily7dayRoll(),
        getTotalCases(), getTotalDeaths(), getColor());
    }
  }

  /**
   * Simple class to read files; including here for simplicity's sake
   */
  static public class EasyReader
  {
    private File           file_     = null;
    private BufferedReader reader_   = null;
    private boolean        readable_ = false;

    public String         getFileName() { return file_ == null ? "" : file_.getAbsolutePath(); }
    public File           getFile    () { return file_    ; }
    public BufferedReader getReader  () { return reader_  ; }
    public boolean        isReadable () { return readable_; }

    public EasyReader()
    {
    }

    public EasyReader(String pFileName)
    {
      this(new File(pFileName));
    }

    public EasyReader(File pFile)
    {
      file_ = pFile;
      open();
    }

    public static String fetchContents(File pFile)
    {
      return (pFile == null) ? "" : fetchContents(pFile.getPath());
    }

    public static String fetchContents(String pFileName)
    {
      StringBuilder builder = new StringBuilder();

      EasyReader reader = new EasyReader(pFileName);

      if (reader.open())
      {
        String line;
        while ((line = reader.readLine()) != null)
        {
          builder.append(line).append("\n");
        }

        reader.close();
      }

      return builder.toString();
    }

    public void reset()
    {
      open();
    }

    public boolean open()
    {
      readable_ = false;

      try
      {
        reader_ = new BufferedReader(new FileReader(file_));
      }
      catch (Exception e)
      {
        reader_ = null;
        return false;
      }

      readable_ = true;

      return readable_;
    }

    public void close()
    {
      if (reader_ != null)
      {
        try
        {
          reader_.close();
        }
        catch (IOException e)
        {
        }
      }
      readable_ = false;
    }

    public String readLine()
    {
      if (!readable_)
      {
        return null;
      }

      try
      {
        return reader_.readLine();
      }
      catch (Exception e)
      {
        return null;
      }
    }
  }

  /**
   * Simple class to write files; incuded here for simplicity's sake
   */
  static public class EasyWriter
  {
    private File           file_      = null;
    private BufferedWriter writer_    = null;
    private boolean        writeable_ = false;

    public String         getFileName() { return file_ == null ? "" : file_.getAbsolutePath(); }
    public File           getFile    () { return file_     ; }
    public BufferedWriter getWriter  () { return writer_   ; }
    public boolean        isWriteable() { return writeable_; }

    static private long s_total_LinesWritter = 0L;
    static public long getTotalLinesWritten() { return s_total_LinesWritter; }

    public static void dumpStringToFile(File    pFile,
                                        boolean pAppend,
                                        String  pMessage)
    {
      try
      {
        pFile.getParentFile().mkdirs();
        EasyWriter writer = new EasyWriter(pFile, pAppend);
        writer.writeLine(pMessage);
        writer.close();
      }
      catch (Exception e)
      {
        e.printStackTrace();
      }
    }

    public static void dumpStringToFilename(String  pFilename,
                                            boolean pAppend,
                                            String  pMessage)
    {
      if ((pFilename == null) || pFilename.isEmpty())
      {
        return;
      }

      dumpStringToFile(new File(pFilename), pAppend, pMessage);
    }

    public EasyWriter()
    {
    }

    public EasyWriter(String pFileName)
    {
      this(new File(pFileName));
    }

    public EasyWriter(File pFile)
    {
      this (pFile, false);
    }

    public EasyWriter(File    pFile,
                      boolean pAppend)
    {
      file_ = pFile;
      open(pAppend);
    }

    public EasyWriter(File   pFile,
                      String pOutput)
    {
      file_ = pFile;
      open();
      writeLine(pOutput);
      close();
    }


    public boolean open()
    {
      return open(false);
    }

    public boolean open(boolean pAppend)
    {
      writeable_ = false;

      try
      {
        file_.getParentFile().mkdirs();
        writer_ = new BufferedWriter(new FileWriter(file_, pAppend));
      }
      catch (Exception e)
      {
        writer_ = null;
        return false;
      }

      writeable_ = true;

      return writeable_;
    }

    public void close()
    {
      if (writer_ != null)
      {
        try
        {
          writer_.flush();
          writer_.close();
        }
        catch (IOException e)
        {
        }
      }
      writeable_ = false;
    }

    public void writeLine(String pLine)
    {
      if (!writeable_)
      {
        return;
      }
      try
      {
        writer_.write(pLine.endsWith("\n") ? pLine : pLine + "\n");
        s_total_LinesWritter += pLine.split("\n").length;
      }
      catch (Exception e)
      {
      }
    }
  }

  /**
   * Historical data
   */
  public static class RiskData
  {
    private static List<String> s_dates = new ArrayList<>();
    public static List<String> getDates() { return s_dates; }

    private static HashMap<Integer, RiskData> s_riskData = new HashMap<>();
    public static HashMap<Integer, RiskData> getRiskData() { return s_riskData; }

    static public void setup()
    {
      String[] lines = EasyReader.fetchContents("./riskData.txt").split("\n");
      String[] headerColumns = lines[0].split("\t");

      for (int loop = 2; loop < headerColumns.length; ++loop)
      {
        s_dates.add(headerColumns[loop]);
      }

      List<String> per100KLines = new ArrayList<>();

      for (int lineIndex = 1; lineIndex < lines.length; ++lineIndex)
      {
        String line = lines[lineIndex];
        if (!line.contains("Daily new cases per 100k people")) continue;

        per100KLines.add(line);
      }

      for (String line : per100KLines)
      {
        RiskData riskData = new RiskData(headerColumns, line.split("\t"));
        s_riskData.put(riskData.getCountyId(), riskData);
      }
    }

    private String _countyName;
    public String getCountyName() { return _countyName; }

    private int _countyId;
    public int getCountyId() { return _countyId; }

    private HashMap<String, Double> _per100KValueMap = new HashMap<>();
    public HashMap<String, Double> getPer100KValueMap() { return _per100KValueMap; }

    public RiskData(String[] pHeaderColumns, String[] pDataColumns)
    {
      _countyName = pDataColumns[0].replace("\"", "");
      CountyInfo info = s_countyByNameMap.get(_countyName);
      if (info == null)
      {
        System.out.println(String.format("Cannot find county: '%s'", _countyName));
        _countyId = -1;
      }
      else
      {
        _countyId = s_countyByNameMap.get(_countyName).getCountyId();
      }

      for (int loop = 2; (loop < pHeaderColumns.length) && (loop < pDataColumns.length); ++loop)
      {
        _per100KValueMap.put(pHeaderColumns[loop], Double.valueOf(pDataColumns[loop]));
      }
    }

    public String toString()
    {
      return String.format("County: %s/%d [%d]", getCountyName(), getCountyId(), getPer100KValueMap().values().size());
    }
  }

  private static HashMap<Integer, CountyInfo> s_countyByIdMap;
  private static HashMap<String, CountyInfo> s_countyByNameMap;
  private static HashMap<Integer, CountyPolygon> s_countyPolygonByIdMap;
  private static List<Arc> s_arcList;

  private static Transform s_scale;
  private static Transform s_translate;

  static
  {
    s_topologyJson = new Gson().fromJson(EasyReader.fetchContents("./covid_topology.json"), TopologyJson.class);

    double scaleXFactor = 4 * 8;
    double scaleYFactor = 5 * 8;
    s_scale = new Transform(scaleXFactor * s_topologyJson.transform.scale[0], scaleYFactor * s_topologyJson.transform.scale[1]);
    s_translate = new Transform(s_topologyJson.transform.translate[0], s_topologyJson.transform.translate[1]);

    s_arcList = new ArrayList<>();

    int[][][] arcs = s_topologyJson.arcs;

    for (int loop = 0; loop < arcs.length; ++loop)
    {
      s_arcList.add(new Arc(arcs[loop]));
    }

    s_jobsDataJson = new Gson().fromJson(EasyReader.fetchContents("./covid_county-data.json"), JobsDataJson.class);

    s_countyByIdMap = new HashMap<>();
    s_countyByNameMap = new HashMap<>();

    for (int loop = 0; loop < s_jobsDataJson.results[0].result.data.dsr.DS[0].PH[0].DM0.length; ++loop)
    {
      CountyInfo countyInfo = new CountyInfo(s_jobsDataJson.results[0].result.data.dsr.DS[0].PH[0].DM0[loop]);
      s_countyByIdMap.put(countyInfo.getCountyId(), countyInfo);
      s_countyByNameMap.put(countyInfo.getCounty(), countyInfo);
    }

    s_countyPolygonByIdMap = new HashMap<>();

    for (int loop = 0; loop < s_topologyJson.objects.counties20m.geometries.length; ++loop)
    {
      CountyPolygon countyPolygon = new CountyPolygon(s_topologyJson.objects.counties20m.geometries[loop]);
      s_countyPolygonByIdMap.put(countyPolygon.getCountyId(), countyPolygon);
    }

    RiskData.setup();
  }

  public static void main(String[] args)
  {
    CovidReader reader = new CovidReader();

    for (String date : RiskData.getDates())
    {
      reader.process(date);
    }
  }

  public CovidReader()
  {

  }

  static int svgCount = 0;
  static int s_mapBuffer = 20;

  public void process(String pDate)
  {
    System.out.println(pDate);
    StringBuilder outerBuilder = new StringBuilder();
    StringBuilder builder = new StringBuilder();
    outerBuilder.append("<html>\n<body>\n");

    int width = (int) Math.ceil(s_maxX * s_scale.getX());
    int height = (int) Math.ceil(s_maxY * s_scale.getY());

    if (width%2 != 0) ++width;
    if (height%2 != 0) ++height;

    /**
     * Calculate average cases per 100K
     */

    double per100KTotal = 0.0;
    int exceptions = 0;

    for (RiskData riskData : RiskData.getRiskData().values())
    {
      try
      {
        Double per100K = riskData.getPer100KValueMap().get(pDate);
        if (per100K != null)
        {
          per100KTotal += per100K;
        }
        else
        {
          ++exceptions;
        }
      }
      catch (Exception e)
      {
        System.out.println(pDate + "/" + riskData);
        e.printStackTrace();
      }
    }

    double per100KOverall = per100KTotal / (RiskData.getRiskData().values().size() - exceptions);

    builder.append(String.format("<svg width=\"%d\" height=\"%d\" style=\"position: absolute; margin-top: 0px;\">\n", width + 2*s_mapBuffer, height + 2*s_mapBuffer));
    builder.append(              "\t<rect width=\"100%\" height=\"100%\" style=\"fill: rgb(255,255,255);\"></rect>\n");

    builder.append("\t<rect width=\"100%\" height=\"100%\" " + String.format("style=\"opacity:0.25; fill: %s;\"></rect>\n", getColorForPer100K(per100KOverall)));

    builder.append(String.format("\t<text x=\"%d\" y=\"%d\" style=\"font: italic 40px serif; fill: black;\">%s</text>\n", (int) Math.rint(width*0.85), (int) Math.rint(height), pDate));

    double rx = width * 0.925;
    double ry = height * 0.5;
    double sx = width/30;
    double sy = height/30;
    double textYDelta = sy * 0.6;

    builder.append(String.format("\t<rect x=\"%f\" y=\"%f\" width=\"%f\" height=\"%f\" style=\"opacity:1.0; fill: %s;\"></rect>\n", rx, ry, sx, sy, getColorForPer100K(0.5)));
    builder.append(String.format("\t<text x=\"%f\" y=\"%f\" style=\"font: italic 20px serif; fill: black;\">%s</text>\n", rx + sx + 10, ry + textYDelta, "< 1.0"));

    ry += sy * 1.5;

    builder.append(String.format("\t<rect x=\"%f\" y=\"%f\" width=\"%f\" height=\"%f\" style=\"opacity:1.0; fill: %s;\"></rect>\n", rx, ry, sx, sy, getColorForPer100K(10)));
    builder.append(String.format("\t<text x=\"%f\" y=\"%f\" style=\"font: italic 20px serif; fill: black;\">%s</text>\n", rx + sx + 10, ry + textYDelta, "10"));

    ry += sy * 1.5;

    builder.append(String.format("\t<rect x=\"%f\" y=\"%f\" width=\"%f\" height=\"%f\" style=\"opacity:1.0; fill: %s;\"></rect>\n", rx, ry, sx, sy, getColorForPer100K(15)));
    builder.append(String.format("\t<text x=\"%f\" y=\"%f\" style=\"font: italic 20px serif; fill: black;\">%s</text>\n", rx + sx + 10, ry + textYDelta, "15"));

    ry += sy * 1.5;

    builder.append(String.format("\t<rect x=\"%f\" y=\"%f\" width=\"%f\" height=\"%f\" style=\"opacity:1.0; fill: %s;\"></rect>\n", rx, ry, sx, sy, getColorForPer100K(100)));
    builder.append(String.format("\t<text x=\"%f\" y=\"%f\" style=\"font: italic 20px serif; fill: black;\">%s</text>\n", rx + sx + 10, ry + textYDelta, "100"));

    ry += sy * 1.5;

    builder.append(String.format("\t<rect x=\"%f\" y=\"%f\" width=\"%f\" height=\"%f\" style=\"opacity:1.0; fill: %s;\"></rect>\n", rx, ry, sx, sy, getColorForPer100K(250)));
    builder.append(String.format("\t<text x=\"%f\" y=\"%f\" style=\"font: italic 20px serif; fill: black;\">%s</text>\n", rx + sx + 10, ry + textYDelta, "250"));

    ry += sy * 1.5;

    builder.append(String.format("\t<rect x=\"%f\" y=\"%f\" width=\"%f\" height=\"%f\" style=\"opacity:1.0; fill: %s;\"></rect>\n", rx, ry, sx, sy, getColorForPer100K(500)));
    builder.append(String.format("\t<text x=\"%f\" y=\"%f\" style=\"font: italic 20px serif; fill: black;\">%s</text>\n", rx + sx + 10, ry + textYDelta, "500"));

    builder.append("\t<g style=\"stroke-width:0.05; stroke: rgb(255, 255, 255); fill: rgb(180, 180, 180);\">\n");

    for (CountyPolygon countyPolygon : s_countyPolygonByIdMap.values())
    {
      int countyId = countyPolygon.getCountyId();

      CountyInfo countyInfo = s_countyByIdMap.get(countyId);
      if (countyInfo == null)
      {
        System.out.println("Cannot find county: " + countyId);
        continue;
      }

      for (Arc arc : countyPolygon.getArcs())
      {
        if (arc.getPoints() == null) continue;

        Point pointStart = arc.getPoints().get(0);
        builder.append(String.format("\t\t<path d=\"M%f,%f", transformedX(pointStart.getX()), transformedY(pointStart.getY())));

        for (int point = 1; point < arc.getPoints().size(); ++point)
        {
          Point pointNext = arc.getPoints().get(point);

          builder.append(String.format("L%f,%f", transformedX(pointNext.getX()), transformedY(pointNext.getY())));
        }

        RiskData riskData = RiskData.getRiskData().get(countyId);

        Double per100K = riskData.getPer100KValueMap().get(pDate);

        String color = getColorForPer100K(per100K == null ? -1 : per100K);
        double opacity = 0.5 + per100K/100;
        if (opacity > 1) opacity = 1;

        builder.append(String.format("Z\" opacity=\"%f\" style=\"fill: %s;\"></path>\n", opacity, color));
      }
    }

    builder.append("\t</g>\n</svg>");
    outerBuilder.append(builder.toString()).append("\n</body>\n</html>");

    EasyWriter.dumpStringToFilename(String.format("/Users/joseph.wood/Desktop/covidData/svg/testCovid_%03d.svg", svgCount++), false, builder.toString());
    EasyWriter.dumpStringToFilename(String.format("/Users/joseph.wood/Desktop/covidData/html/testCovid_%s.html", pDate), false, outerBuilder.toString());
  }

  public String getColorForPer100K(double pPer100K)
  {
    if (pPer100K < 0)
    {
      return "rgb(180, 180, 180)";
    }
    else if (pPer100K < 1.0)
    {
      return "rgb(0, 255, 0)";
    }
    else if (pPer100K <= 10.0)
    {
      double red = pPer100K * 25.5;
      double green = 255;

      if (red   > 255) red   = 255;
      if (green > 255) green = 255;

      if (red   < 0) red   = 0;
      if (green < 0) green = 0;

      String color = String.format("rgb(%d, %d, 0)", (int)Math.rint(red), (int)Math.rint(green));
      return color;
    }
    else if (pPer100K < 25.0)
    {
      double red = 255;
      double green = 255 - pPer100K * 10;

      if (red   > 255) red   = 255;
      if (green > 255) green = 255;

      if (red   < 0) red   = 0;
      if (green < 0) green = 0;

      String color = String.format("rgb(%d, %d, 0)", (int)Math.rint(red), (int)Math.rint(green));
      return color;
    }
    else if (pPer100K < 100.0)
    {
      double red = 255;
      double green = (100.0 - pPer100K);
      double blue = green;

      if (red   > 255) red   = 255;
      if (green > 255) green = 255;
      if (blue  > 255) blue  = 255;

      if (red   < 0) red   = 0;
      if (green < 0) green = 0;
      if (blue  < 0) blue  = 0;

      String color = String.format("rgb(%d, %d, %d)", (int)Math.rint(red), (int)Math.rint(green), (int)Math.rint(blue));
      return color;
    }
    else if (pPer100K < 250.0)
    {
      double red = 255;
      double blue = pPer100K ;

      if (red   > 255) red   = 255;
      if (blue  > 255) blue  = 255;

      if (red   < 0) red   = 0;
      if (blue  < 0) blue  = 0;

      String color = String.format("rgb(%d, 0, %d)", (int)Math.rint(red), (int)Math.rint(blue));
      return color;
    }
    else
    {
      double red = 255 - (pPer100K - 250);
      double green = 0;
      double blue = 255;

      if (red   > 255) red   = 255;
      if (green > 255) green = 255;
      if (blue  > 255) blue  = 255;

      if (red   < 0) red   = 0;
      if (green < 0) green = 0;
      if (blue  < 0) blue  = 0;

      String color = String.format("rgb(%d, %d, %d)", (int)Math.rint(red), (int)Math.rint(green), (int)Math.rint(blue));
      return color;
    }
  }

  public double transformedX(int pX)
  {
    return s_mapBuffer + (pX + s_translate.getX()) * s_scale.getX();
  }

  public double transformedY(int pY)
  {
    return s_mapBuffer + (s_maxY - (pY + s_translate.getY())) * s_scale.getY();
  }
}
