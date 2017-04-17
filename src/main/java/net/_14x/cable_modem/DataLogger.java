package net._14x.cable_modem;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.rrd4j.core.RrdDb;
import org.rrd4j.core.RrdDef;
import org.rrd4j.core.Sample;
import org.rrd4j.core.Util;
import org.rrd4j.graph.RrdGraph;
import org.rrd4j.graph.RrdGraphDef;

import java.awt.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.Calendar;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.rrd4j.ConsolFun.MAX;
import static org.rrd4j.DsType.GAUGE;

/**
 * Hello world!
 */
public class DataLogger
{
    private static String cableModemUrl;
    private static String rrdDatabaseFileName;
    private static Integer numberOfDownstreamChannels;
    private static Integer step;
    private static Integer sampleTime;
    private static Integer heartbeat;
    private static Integer numberOfSamples;
    static long START;
    static long END;
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static RrdDb rrdDb;
    private static Color graphWarningAreaColor = new Color(0xff, 0, 0x00, 0x80);
    private static LocalDate lastCheck = LocalDate.now();

    static final Runnable logger = new Runnable()
    {
        public void run()
        {
            try
            {
                if(isNewDay())
                {
                    String dataString = getYesterdayDateString();

                    File dateDir = new File(dataString);
                    // if the directory does not exist, create it
                    if (!dateDir.exists()) {
                        dateDir.mkdir();
                    }

                    rrdDb.exportXml(dataString + "/cable_modem.xml");
                    createSnrGraph(86400, dataString + "/cable-modem_signal-to-noise-ratio.png");
                    createPowerLevelGraph(86400, dataString + "/cable-modem_power-level.png");
                }

                Document doc = Jsoup.connect(cableModemUrl).get();
                Element downstreamChannelTable = doc.select("body > form > div > table.dataTable > tbody > tr > td:nth-child(1) > table:nth-child(7) > tbody > tr:nth-child(3) > td.Item3 > table").get(0);
                Elements downstreamChannelTableRows = downstreamChannelTable.select("tr");

                long time = Util.getTime();
                System.out.println("Sample Time: " + time);

                Sample channelSample = rrdDb.createSample();
                for (int r = 1; r <= numberOfDownstreamChannels; r++) //first row is the col names so skip it.
                {
                    Element row = downstreamChannelTableRows.get(r);
                    Elements cols = row.select("td");

                    //downstreamChannels.add(new HashMap<String, String>();

                    Integer channel = Integer.parseInt(cols.get(0).text());
                    Float powerLevel = Float.parseFloat(cols.get(1).text());
                    Float signalToNoiseRatio = Float.parseFloat(cols.get(2).text());

                    System.out.println("Channel: " + channel + ", Power Level: " + powerLevel + ", SNR: " + signalToNoiseRatio);

                    channelSample.setTime(time);
                    channelSample.setValue("ch" + r + "PowerLevel", powerLevel);
                    channelSample.setValue("ch" + r + "Snr", signalToNoiseRatio);
                }

                channelSample.update();
                System.out.println();

                // Create Graph
                createSnrGraph(3600, "cable-modem_signal-to-noise-ratio-3600.png");
                createPowerLevelGraph(3600, "cable-modem_power-level-3600.png");
            }
            catch (SocketException e)
            {
                System.out.println(e.getMessage());
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }

    };

    public static boolean isNewDay() {
        LocalDate today = LocalDate.now();
        boolean ret = lastCheck == null || today.isAfter(lastCheck);
        lastCheck = today;
        return ret;
    }

    private static Date yesterday() {
        final Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DATE, -1);
        return cal.getTime();
    }

    private static String getYesterdayDateString() {
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        return dateFormat.format(yesterday());
    }

    public static void main(String[] args)
    {
        System.setProperty("java.awt.headless", "true");

        parseConfigurationFile();

        rrdDb = defineRrd();

        java.util.Date startTimestamp = new java.util.Date((long) START * 1000);
        java.util.Date endTimestamp = new java.util.Date((long) END * 1000);

        System.out.println("Start: " + START + ", " + startTimestamp);
        System.out.println("RRD Rotation: " + END + ", " + endTimestamp);

        final ScheduledFuture<?> loggerHandle = scheduler.scheduleAtFixedRate(logger, 0, step, SECONDS);
    }

    private static void parseConfigurationFile()
    {
        Properties prop = new Properties();
        InputStream input = null;

        try
        {
            input = new FileInputStream("config.properties");
            prop.load(input);   // load a properties file

            // get the property value and print it out
            cableModemUrl = prop.getProperty("cableModemStatusPage");
            rrdDatabaseFileName = prop.getProperty("rrdDatabaseFileName");
            numberOfDownstreamChannels = Integer.valueOf(prop.getProperty("numberOfDownstreamChannels"));
            step = Integer.valueOf(prop.getProperty("step"));
            sampleTime = Integer.valueOf(prop.getProperty("sampleTime"));
            heartbeat = Integer.valueOf(prop.getProperty("heartbeat"));

            numberOfSamples = sampleTime / step;
            START = Util.getTime();
            END = START + sampleTime;
        }
        catch (IOException ex)
        {
            ex.printStackTrace();
        }
        finally
        {
            if (input != null)
            {
                try
                {
                    input.close();
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            }
        }
    }


    private static RrdDb defineRrd()
    {
        // first, define the RRD
        RrdDef rrdDef = new RrdDef(rrdDatabaseFileName, START, step);

        rrdDef.addDatasource("ch1PowerLevel", GAUGE, heartbeat, -Double.MAX_VALUE, Double.MAX_VALUE);
        rrdDef.addDatasource("ch1Snr", GAUGE, heartbeat, -Double.MAX_VALUE, Double.MAX_VALUE);

        rrdDef.addDatasource("ch2PowerLevel", GAUGE, heartbeat, -Double.MAX_VALUE, Double.MAX_VALUE);
        rrdDef.addDatasource("ch2Snr", GAUGE, heartbeat, -Double.MAX_VALUE, Double.MAX_VALUE);

        rrdDef.addDatasource("ch3PowerLevel", GAUGE, heartbeat, -Double.MAX_VALUE, Double.MAX_VALUE);
        rrdDef.addDatasource("ch3Snr", GAUGE, heartbeat, -Double.MAX_VALUE, Double.MAX_VALUE);

        rrdDef.addDatasource("ch4PowerLevel", GAUGE, heartbeat, -Double.MAX_VALUE, Double.MAX_VALUE);
        rrdDef.addDatasource("ch4Snr", GAUGE, heartbeat, -Double.MAX_VALUE, Double.MAX_VALUE);

        rrdDef.addDatasource("ch5PowerLevel", GAUGE, heartbeat, -Double.MAX_VALUE, Double.MAX_VALUE);
        rrdDef.addDatasource("ch5Snr", GAUGE, heartbeat, -Double.MAX_VALUE, Double.MAX_VALUE);

        rrdDef.addDatasource("ch6PowerLevel", GAUGE, heartbeat, -Double.MAX_VALUE, Double.MAX_VALUE);
        rrdDef.addDatasource("ch6Snr", GAUGE, heartbeat, -Double.MAX_VALUE, Double.MAX_VALUE);

        rrdDef.addDatasource("ch7PowerLevel", GAUGE, heartbeat, Float.MIN_VALUE, Double.NaN);
        rrdDef.addDatasource("ch7Snr", GAUGE, heartbeat, -Double.MAX_VALUE, Double.MAX_VALUE);

        rrdDef.addDatasource("ch8PowerLevel", GAUGE, heartbeat, -Double.MAX_VALUE, Double.MAX_VALUE);
        rrdDef.addDatasource("ch8Snr", GAUGE, heartbeat, -Double.MAX_VALUE, Double.MAX_VALUE);

        rrdDef.addArchive(MAX, 0.5, 1, numberOfSamples);

        // then, create a RrdDb from the definition and start adding data
        try
        {
            return new RrdDb(rrdDef);
        }
        catch (IOException e)
        {
            e.printStackTrace();
            return null;
        }
    }

    private static void closeRrd(RrdDb rrdDb)
    {
        try
        {
            System.out.println("Attempting to close RdbDb.");
            rrdDb.close();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    private static void createSnrGraph(long startOffset, String filename) throws IOException
    {
        // then create a graph definition
        RrdGraphDef gDef = new RrdGraphDef();
        gDef.setWidth(3840);
        gDef.setHeight(2160);
        gDef.setFilename(filename);
        gDef.setStartTime(Util.getTime() - startOffset);
        gDef.setEndTime(Util.getTime());
        gDef.setTitle("Signal to Noise Ratio");
        gDef.setVerticalLabel("dB");

        //gDef.setDrawXGrid(true);
        //gDef.setDrawYGrid(true);

        //gDef.setRigid(true);
        //gDef.setMaxValue(41.0);
        //gDef.setMinValue(37.0);

        Float line_width = 1.0F;

        gDef.datasource("ch1Snr", rrdDatabaseFileName, "ch1Snr", MAX);
        gDef.line("ch1Snr", Color.GREEN, "Channel 1 SNR", line_width);

        //gDef.datasource("ch1SnrAvg", "ch1Snr,10,TREND");
        //gDef.line("ch1SnrAvg", Color.BLACK, "Channel 1 SNR Average", line_width);

        gDef.datasource("ch2Snr", rrdDatabaseFileName, "ch2Snr", MAX);
        gDef.line("ch2Snr", Color.BLUE, "Channel 2 SNR", line_width);

        gDef.datasource("ch3Snr", rrdDatabaseFileName, "ch3Snr", MAX);
        gDef.line("ch3Snr", Color.RED, "Channel 3 SNR", line_width);

        gDef.datasource("ch4Snr", rrdDatabaseFileName, "ch4Snr", MAX);
        gDef.line("ch4Snr", Color.CYAN, "Channel 4 SNR", line_width);

        gDef.datasource("ch5Snr", rrdDatabaseFileName, "ch5Snr", MAX);
        gDef.line("ch5Snr", Color.DARK_GRAY, "Channel 5 SNR", line_width);

        gDef.datasource("ch6Snr", rrdDatabaseFileName, "ch6Snr", MAX);
        gDef.line("ch6Snr", Color.MAGENTA, "Channel 6 SNR", line_width);

        gDef.datasource("ch7Snr", rrdDatabaseFileName, "ch7Snr", MAX);
        gDef.line("ch7Snr", Color.ORANGE, "Channel 7 SNR", line_width);

        gDef.datasource("ch8Snr", rrdDatabaseFileName, "ch8Snr", MAX);
        gDef.line("ch8Snr", Color.YELLOW, "Channel 8 SNR", line_width);

        gDef.area(33.0, graphWarningAreaColor, false);

        gDef.setImageFormat("png");

        // then actually draw the graph
        RrdGraph graph = new RrdGraph(gDef); // will create the graph in the path specified
    }

    private static void createPowerLevelGraph(long startOffset, String filename) throws IOException
    {
        // then create a graph definition
        RrdGraphDef gDef = new RrdGraphDef();
        gDef.setWidth(3840);
        gDef.setHeight(2160);
        gDef.setFilename(filename);
        gDef.setStartTime(Util.getTime() - startOffset);
        gDef.setEndTime(Util.getTime());
        gDef.setTitle("Power Level");
        gDef.setVerticalLabel("dBmV");

        //gDef.setDrawXGrid(true);
        //gDef.setDrawYGrid(true);

        //gDef.setRigid(true);
        //gDef.setMaxValue(20.0);
        //gDef.setMinValue(-20.0);

        Float line_width = 1.0F;

        gDef.datasource("ch1PowerLevel", rrdDatabaseFileName, "ch1PowerLevel", MAX);
        gDef.line("ch1PowerLevel", Color.GREEN, "Channel 1 Power Level", line_width);

        gDef.datasource("ch2PowerLevel", rrdDatabaseFileName, "ch2PowerLevel", MAX);
        gDef.line("ch2PowerLevel", Color.BLUE, "Channel 2 SNR", line_width);

        gDef.datasource("ch3PowerLevel", rrdDatabaseFileName, "ch3PowerLevel", MAX);
        gDef.line("ch3PowerLevel", Color.RED, "Channel 3 SNR", line_width);

        gDef.datasource("ch4PowerLevel", rrdDatabaseFileName, "ch4PowerLevel", MAX);
        gDef.line("ch4PowerLevel", Color.CYAN, "Channel 4 SNR", line_width);

        gDef.datasource("ch5PowerLevel", rrdDatabaseFileName, "ch5PowerLevel", MAX);
        gDef.line("ch5PowerLevel", Color.DARK_GRAY, "Channel 5 SNR", line_width);

        gDef.datasource("ch6PowerLevel", rrdDatabaseFileName, "ch6PowerLevel", MAX);
        gDef.line("ch6PowerLevel", Color.MAGENTA, "Channel 6 SNR", line_width);

        gDef.datasource("ch7PowerLevel", rrdDatabaseFileName, "ch7PowerLevel", MAX);
        gDef.line("ch7PowerLevel", Color.ORANGE, "Channel 7 SNR", line_width);

        gDef.datasource("ch8PowerLevel", rrdDatabaseFileName, "ch8PowerLevel", MAX);
        gDef.line("ch8PowerLevel", Color.YELLOW, "Channel 8 SNR", line_width);

        gDef.hspan(15.0, 20.0, graphWarningAreaColor);
        gDef.hspan(-20.0, -15.0, graphWarningAreaColor);

        gDef.setImageFormat("png");

        // then actually draw the graph
        RrdGraph graph = new RrdGraph(gDef); // will create the graph in the path specified
    }

}
