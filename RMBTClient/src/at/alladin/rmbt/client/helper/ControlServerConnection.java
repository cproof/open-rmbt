/*******************************************************************************
 * Copyright 2013 alladin-IT OG
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package at.alladin.rmbt.client.helper;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;
import java.util.TimeZone;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import at.alladin.rmbt.client.Ping;
import at.alladin.rmbt.client.RMBTTestParameter;
import at.alladin.rmbt.client.SpeedItem;
import at.alladin.rmbt.client.TotalTestResult;
import at.alladin.rmbt.client.ndt.UiServicesAdapter;

public class ControlServerConnection
{
    
    // url to make request
    private URI hostUri;
    
    private boolean testEncryption;
    
    private final JSONParser jParser;
    
    private String testToken = "";
    private String testId = "";
    private String testUuid = "";
    
    private long testTime = 0;
    
    private String testHost = "";
    private int testPort = 0;
    private String remoteIp = "";
    private String serverName;
    private String provider;
    
    private int testDuration = 0;
    private int testNumThreads = 0;
    
    private String clientUUID = "";
    
    private URI resultURI;
    
    private String errorMsg = null;
    
    private boolean hasError = false;
    
    /**
     * @param args
     */
    public ControlServerConnection()
    {
        
        // Creating JSON Parser instance
        jParser = new JSONParser();
        hasError = false;
        
    }
    
    private static URI getUri(final boolean encryption, final String host, final String pathPrefix, final int port,
            final String path)
    {
        try
        {
            final String protocol = encryption ? "https" : "http";
            final int defaultPort = encryption ? 443 : 80;
            final String totalPath = (pathPrefix != null ? pathPrefix : "") + Config.RMBT_CONTROL_PATH + path;
            
            if (defaultPort == port)
                return new URL(protocol, host, totalPath).toURI();
            else
                return new URL(protocol, host, port, totalPath).toURI();
        }
        catch (final MalformedURLException e)
        {
            return null;
        }
        catch (final URISyntaxException e)
        {
            return null;
        }
    }
    
    public String requestNewTestConnection(final String host, final String pathPrefix, final int port,
            final boolean encryption, final ArrayList<String> geoInfo, final String uuid, final String clientType,
            final String clientName, final String clientVersion, final JSONObject additionalValues)
    {
        
        // url to make request to
        
        clientUUID = uuid;
        
        hostUri = getUri(encryption, host, pathPrefix, port, Config.RMBT_CONTROL_MAIN_URL);
        
        System.out.println("Connection to " + hostUri);
        
        final JSONObject regData = new JSONObject();
        
        try
        {
            regData.put("uuid", uuid);
            regData.put("client", clientName);
            regData.put("version", Config.RMBT_VERSION_NUMBER);
            regData.put("type", clientType);
            regData.put("softwareVersion", clientVersion);
            regData.put("softwareRevision", RevisionHelper.getVerboseRevision());
            regData.put("language", Locale.getDefault().getLanguage());
            regData.put("timezone", TimeZone.getDefault().getID());
            regData.put("time", System.currentTimeMillis());
            
            if (geoInfo != null)
            {
                final JSONObject locData = new JSONObject();
                locData.put("time", geoInfo.get(0));
                locData.put("lat", geoInfo.get(1));
                locData.put("long", geoInfo.get(2));
                locData.put("accuracy", geoInfo.get(3));
                locData.put("altitude", geoInfo.get(4));
                locData.put("bearing", geoInfo.get(5));
                locData.put("speed", geoInfo.get(6));
                locData.put("provider", geoInfo.get(7));
                
                regData.accumulate("location", locData);
            }
            
            addToJSONObject(regData, additionalValues);
            
        }
        catch (final JSONException e1)
        {
            hasError = true;
            errorMsg = "Error gernerating request";
            // e1.printStackTrace();
        }
        
        // getting JSON string from URL
        final JSONObject response = jParser.sendJSONToUrl(hostUri, regData);
        
        if (response != null)
            try
            {
                final JSONArray errorList = response.getJSONArray("error");
                
                // System.out.println(response.toString(4));
                
                if (errorList.length() == 0)
                {
                    
                    clientUUID = response.optString("uuid", clientUUID);
                    
                    testToken = response.getString("test_token");
                    
                    testId = response.getString("test_id");
                    testUuid = response.getString("test_uuid");
                    
                    testTime = System.currentTimeMillis() + 1000 * response.getLong("test_wait");
                    
                    testHost = response.getString("test_server_address");
                    testPort = response.getInt("test_server_port");
                    testEncryption = response.getBoolean("test_server_encryption");
                    serverName = response.optString("test_server_name", null);
                    provider = response.optString("provider", null);
                    
                    testDuration = response.getInt("test_duration");
                    testNumThreads = response.getInt("test_numthreads");
                    
                    remoteIp = response.getString("client_remote_ip");
                    
                    resultURI = new URI(response.getString("result_url"));
                }
                else
                {
                    hasError = true;
                    for (int i = 0; i < errorList.length(); i++)
                    {
                        if (i > 0)
                            errorMsg += "\n";
                        errorMsg += errorList.getString(i);
                    }
                }
                
                // }
            }
            catch (final JSONException e)
            {
                hasError = true;
                errorMsg = "Error parsing server response";
                e.printStackTrace();
            }
            catch (final URISyntaxException e)
            {
                hasError = true;
                errorMsg = "Error parsing server response";
                e.printStackTrace();
            }
        else
        {
            hasError = true;
            errorMsg = "No response";
        }
        
        return errorMsg;
    }
    
    public String sendTestResult(final TotalTestResult result, final JSONObject additionalValues)
    {
        
        if (resultURI != null)
        {
            
            final JSONObject testData = new JSONObject();
            
            try
            {
                
                testData.put("client_uuid", clientUUID);
                testData.put("client_name", Config.RMBT_CLIENT_NAME);
                testData.put("client_version", Config.RMBT_VERSION_NUMBER);
                testData.put("client_language", Locale.getDefault().getLanguage());
                
                testData.put("time", System.currentTimeMillis());
                
                testData.put("test_token", testToken);
                
                testData.put("test_port_remote", result.port_remote);
                testData.put("test_bytes_download", result.bytes_download);
                testData.put("test_bytes_upload", result.bytes_upload);
                testData.put("test_total_bytes_download", result.totalDownBytes);
                testData.put("test_total_bytes_upload", result.totalUpBytes);
                testData.put("test_encryption", result.encryption);
                testData.put("test_ip_local", result.ip_local.getHostAddress());
                testData.put("test_ip_server", result.ip_server.getHostAddress());
                testData.put("test_nsec_download", result.nsec_download);
                testData.put("test_nsec_upload", result.nsec_upload);
                testData.put("test_num_threads", result.num_threads);
                testData.put("test_speed_download", (long) Math.floor(result.speed_download + 0.5d));
                testData.put("test_speed_upload", (long) Math.floor(result.speed_upload + 0.5d));
                testData.put("test_ping_shortest", result.ping_shortest);
                
                final JSONArray pingData = new JSONArray();
                
                if (result.pings != null && !result.pings.isEmpty())
                {
                    for (final Ping ping : result.pings)
                    {
                        final JSONObject pingItem = new JSONObject();
                        pingItem.put("value", ping.client);
                        pingItem.put("value_server", ping.server);
                        pingData.put(pingItem);
                    }
                }
                
                testData.put("pings", pingData);
                
                JSONArray speedDetail = new JSONArray();
                
                if (result.speedItems != null)
                {
                    for (SpeedItem item : result.speedItems)
                        speedDetail.put(item.toJSON());
                }
                
                testData.put("speed_detail", speedDetail);
                
                addToJSONObject(testData, additionalValues);
                
                // System.out.println(testData.toString(4));
                
            }
            catch (final JSONException e1)
            {
                hasError = true;
                errorMsg = "Error gernerating request";
                // e1.printStackTrace();
            }
            
            // getting JSON string from URL
            final JSONObject response = jParser.sendJSONToUrl(resultURI, testData);
            
            if (response != null)
                try
                {
                    final JSONArray errorList = response.getJSONArray("error");
                    
                    // System.out.println(response.toString(4));
                    
                    if (errorList.length() == 0)
                    {
                        
                        // System.out.println("All is fine");
                        
                    }
                    else
                    {
                        hasError = true;
                        for (int i = 0; i < errorList.length(); i++)
                        {
                            if (i > 0)
                                errorMsg += "\n";
                            errorMsg += errorList.getString(i);
                        }
                    }
                    
                    // }
                }
                catch (final JSONException e)
                {
                    hasError = true;
                    errorMsg = "Error parsing server response";
                    e.printStackTrace();
                }
        }
        else
        {
            hasError = true;
            errorMsg = "No URL to send the Data to.";
        }
        
        return errorMsg;
    }
    
    public void sendNDTResult(final String host, final String pathPrefix, final int port, final boolean encryption,
            final String clientUUID, final UiServicesAdapter data, final String testUuid)
    {
        hostUri = getUri(encryption, host, pathPrefix, port, Config.RMBT_CONTROL_MAIN_URL);
        this.clientUUID = clientUUID;
        sendNDTResult(data, testUuid);
    }
    
    public void sendNDTResult(final UiServicesAdapter data, final String testUuid)
    {
        final JSONObject testData = new JSONObject();
        
        try
        {
            testData.put("client_uuid", clientUUID);
            testData.put("client_language", Locale.getDefault().getLanguage());
            if (testUuid != null)
                testData.put("test_uuid", testUuid);
            else
                testData.put("test_uuid", this.testUuid);
            testData.put("s2cspd", data.s2cspd);
            testData.put("c2sspd", data.c2sspd);
            testData.put("avgrtt", data.avgrtt);
            testData.put("main", data.sbMain.toString());
            testData.put("stat", data.sbStat.toString());
            testData.put("diag", data.sbDiag.toString());
            
            jParser.sendJSONToUrl(hostUri.resolve(Config.RMBT_CONTROL_NDT_RESULT_URL), testData);
        }
        catch (final JSONException e)
        {
            e.printStackTrace();
        }
    }
    
    private void addToJSONObject(final JSONObject data, final JSONObject additionalValues) throws JSONException
    {
        if (additionalValues != null && additionalValues.length() > 0)
        {
            final JSONArray attr = additionalValues.names();
            for (int i = 0; i < attr.length(); i++)
                data.put(attr.getString(i), additionalValues.get(attr.getString(i)));
        }
    }
    
    public boolean hasError()
    {
        return hasError;
    }
    
    public String getRemoteIp()
    {
        return remoteIp;
    }
    
    public String getClientUUID()
    {
        return clientUUID;
    }
    
    public String getServerName()
    {
        return serverName;
    }
    
    public String getProvider()
    {
        return provider;
    }
    
    public long getTestTime()
    {
        return testTime;
    }
    
    public String getErrorMsg()
    {
        return errorMsg;
    }
    
    public String getTestId()
    {
        return testId;
    }
    
    public String getTestUuid()
    {
        return testUuid;
    }
    
    public RMBTTestParameter getTestParameter()
    {
        return new RMBTTestParameter(testHost, testPort, testEncryption, testToken, testDuration, testNumThreads,
                testTime);
    }
    
}
