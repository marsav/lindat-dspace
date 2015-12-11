/* Created for LINDAT/CLARIN */
package cz.cuni.mff.ufal;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;


import org.apache.log4j.Logger;
import org.dspace.core.ConfigurationManager;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import com.maxmind.geoip.Location;
import com.maxmind.geoip.LookupService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Controller
@RequestMapping("/discojuice")
public class DiscoJuiceFeeds {
    /**
     * log4j logger.
     */
    private static Logger log = Logger.getLogger(DiscoJuiceFeeds.class);

    @RequestMapping(method = RequestMethod.GET, value = {"/feeds"}, params = {"callback"})
    public ModelAndView get(@RequestParam("callback") String callback) {
        return new ModelAndView(new DiscoFeedsView(callback));
    }

}

class DiscoFeedsView implements View{
    private static Logger log = Logger.getLogger(DiscoFeedsView.class);
    private static final String discojuiceURL = "https://static.discojuice.org/feeds/";

    private static String feedsContent;
    private static ReadWriteLock lock = new ReentrantReadWriteLock();
    private static final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

    private String callback;

    static{
        executor.scheduleWithFixedDelay(DiscoFeedsView::update,0,
                ConfigurationManager.getLongProperty("discojuice", "refresh"), TimeUnit.HOURS);
    }

    private static final LookupService locationService;
    /**
     * contains entityIDs of idps we wish to set the country to something different than discojuice feeds suggests
     **/
    private static final Set<String> rewriteCountries;

    static {
        String dbfile = ConfigurationManager.getProperty("usage-statistics", "dbfile");
        LookupService service = null;
        if (dbfile != null) {
            try {
                service = new LookupService(dbfile,
                        LookupService.GEOIP_STANDARD);
            } catch (FileNotFoundException fe) {
                log.error("The GeoLite Database file is missing (" + dbfile + ")! Solr Statistics cannot generate location based reports! Please see the DSpace installation instructions for instructions to install this file.", fe);
            } catch (IOException e) {
                log.error("Unable to load GeoLite Database file (" + dbfile + ")! You may need to reinstall it. See the DSpace installation instructions for more details.", e);
            }
        } else {
            log.error("The required 'dbfile' configuration is missing in solr-statistics.cfg!");
        }
        locationService = service;

        rewriteCountries = new HashSet<String>();
        String propRewriteCountries = ConfigurationManager.getProperty("discojuice", "rewriteCountries");
        for (String country : propRewriteCountries.split(",")) {
            country = country.trim();
            rewriteCountries.add(country);
        }
    }

    public DiscoFeedsView(String callback){
        this.callback = callback;
    }

    public static void update(){
        lock.writeLock().lock();
        try{
           feedsContent = createFeedsContent();
        }finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public String getContentType() {
        return "application/javascript;charset=utf-8";
    }

    @Override
    public void render(Map<String, ?> model, HttpServletRequest request, HttpServletResponse response) throws Exception {
        lock.readLock().lock();
        try {
            if(feedsContent == null || feedsContent.isEmpty()){
                response.sendRedirect(ConfigurationManager.getProperty("lr","lr.shibboleth.discofeed.url"));
            }else{
                response.setContentType(this.getContentType());
                //response.setContentLength(?);
                PrintWriter writer = response.getWriter();
                writer.print(callback);
                writer.print('(');
                writer.print(feedsContent);
                writer.print(')');
                writer.close();
            }
        }finally {
            lock.readLock().unlock();
        }
    }

    public static String createFeedsContent(){
        String feedsConfig = ConfigurationManager.getProperty("discojuice", "feeds");
        String shibbolethDiscoFeedUrl = ConfigurationManager.getProperty("lr","lr.shibboleth.discofeed.url");
        return createFeedsContent(feedsConfig, shibbolethDiscoFeedUrl);
    }
    public static String createFeedsContent(String feedsConfig, String shibbolethDiscoFeedUrl){
        String old_value = System.getProperty("jsse.enableSNIExtension");
        System.setProperty("jsse.enableSNIExtension", "false");

        //Obtain shibboleths discofeed
        final Map<String,JSONObject> shibDiscoEntities = DiscoFeedsView.downloadJSON(shibbolethDiscoFeedUrl)
                .collect(Collectors.toMap(entity -> (String)entity.get("entityID"), Function.identity(),
                        (oldValue,newValue) -> {
                            /* System.err.println(String.format("Duplicite entry %s. Keeping first appearance", oldValue.get("entityID")));*/
                            //We have a lot of duplicites, so just keep the first
                            return oldValue;
                        }
                ));

        //true is the default http://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html
        old_value = (old_value == null) ? "true" : old_value;
        System.setProperty("jsse.enableSNIExtension", old_value);

        Map<String, JSONObject> discoEntities = Arrays.asList(feedsConfig.split(",")).parallelStream()
                .flatMap(feed -> DiscoFeedsView.downloadJSON(discojuiceURL + feed.trim()))
                .filter((JSONObject entity) -> {
                    String entityID = (String)entity.get("entityID");
                    return shibDiscoEntities.containsKey(entityID);
                })
                .map(entity -> {
                    String entityID = (String)entity.get("entityID");
                    if(rewriteCountries.contains(entityID)){
                        String old_country = (String)entity.remove("country");
                        String new_country = guessCountry(shibDiscoEntities.get(entityID));
                        entity.put("country", new_country);
                        log.info(String.format("For %s changed country from %s to %s", entityID, old_country, new_country));
                    }
                    return entity;
                })
                //again ignore dupes and just use first
                .collect(Collectors.toMap(entity -> (String)entity.get("entityID"), Function.identity(), (oldValue, newValue) -> oldValue));

        Map<String, JSONObject> onlyInShib = shibDiscoEntities.entrySet().stream()
                .filter(entry -> discoEntities.containsKey(entry.getKey())) //filter by entityID
                .map(entry -> {
                    entry.getValue().put("country", guessCountry(entry.getValue()));
                    return entry;
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        discoEntities.putAll(onlyInShib);
        String fromShib = onlyInShib.keySet().stream().collect(Collectors.joining("\n"));

        //log shibboleth only entries
        if(fromShib != null && fromShib.length()>0){
            log.info("The following entities were added from shibboleth disco feed.\n" + fromShib.toString());
        }

        return discoEntities.values().stream().collect(Collectors.toCollection(JSONArray::new)).toJSONString();
    }

    private static Stream<JSONObject> downloadJSON(String url){
        JSONParser parser = new JSONParser();
        try {
            URLConnection conn = new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            //Caution does not follow redirects, and even if you set it to http->https is not possible
            Object obj = parser.parse(new InputStreamReader(conn.getInputStream()));
            JSONArray entityArray = (JSONArray) obj;
            Iterator<JSONObject> iterator = entityArray.iterator();
            Iterable<JSONObject> iterable = () -> iterator;
            return StreamSupport.stream(iterable.spliterator(),false);
        }catch (IOException|ParseException e){
            log.error("Failed to obtain/parse "+ url + "\nCheck timeouts, redirects, shibboleth config.\n" + e);
        }
        return Stream.empty();
    }

    private static String guessCountry(JSONObject entity){
    	if(locationService != null && entity.containsKey("InformationURLs")){
    		JSONArray informationURLs = (JSONArray)entity.get("InformationURLs");
    		if(informationURLs.size() > 0){
    			String informationURL = (String)((JSONObject)informationURLs.get(0)).get("value");
    			try{
    				Location location = locationService.getLocation(java.net.InetAddress.getByName(new URL(informationURL).getHost()));
    				if(location != null && location.countryCode != null){
    					return location.countryCode;
    				}else{
    					log.info("Country or location is null for " + informationURL);
    				}
    			}catch(MalformedURLException e){
    				
    			}catch(java.net.UnknownHostException e){
    				
    			}
    		}
    	}
    	String entityID = (String)entity.get("entityID");
        //entityID not necessarily an URL
        try{
            URL url = new URL(entityID);
            String host = url.getHost();
            String topLevel = host.substring(host.lastIndexOf('.')+1);
            if(topLevel.length() == 2 && !topLevel.equalsIgnoreCase("eu")){
                //assume country code
                return topLevel.toUpperCase();
            }
        }catch(MalformedURLException e){

        }
        return "_all_"; //by default add "_all_", better search in dj
    }

    //For testing
    public static void main(String[] args) throws Exception{
        long startTime = System.currentTimeMillis();
        String feeds = DiscoFeedsView.createFeedsContent("edugain, dfn, cesnet, surfnet2, haka, kalmar", "https://lindat.mff.cuni.cz/Shibboleth.sso/DiscoFeed");
        long endTime = System.currentTimeMillis();
        System.out.println((endTime - startTime)/1000);
        //System.out.println(feeds);
    }

}
