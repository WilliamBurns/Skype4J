package com.samczsun.skype4j.internal.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

import org.apache.commons.lang3.StringEscapeUtils;
import org.jsoup.Connection.Method;
import org.jsoup.Connection.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.samczsun.skype4j.Skype;
import com.samczsun.skype4j.StreamUtils;
import com.samczsun.skype4j.chat.Chat;
import com.samczsun.skype4j.chat.ChatMessage;
import com.samczsun.skype4j.chat.User.Role;
import com.samczsun.skype4j.events.EventDispatcher;
import com.samczsun.skype4j.events.SkypeEventDispatcher;
import com.samczsun.skype4j.events.chat.TopicChangeEvent;
import com.samczsun.skype4j.events.chat.message.MessageReceivedEvent;
import com.samczsun.skype4j.events.chat.user.RoleUpdateEvent;
import com.samczsun.skype4j.events.chat.user.UserAddEvent;
import com.samczsun.skype4j.events.chat.user.UserRemoveEvent;
import com.samczsun.skype4j.exceptions.SkypeException;

public class WebSkype implements Skype {
    private static final String LOGIN_URL = "https://login.skype.com/login?client_id=578134&redirect_uri=https%3A%2F%2Fweb.skype.com";
    private static final String PING_URL = "https://web.skype.com/api/v1/session-ping";
    private static final String SUBSCRIPTIONS_URL = "https://client-s.gateway.messenger.live.com/v1/users/ME/endpoints/SELF/subscriptions";
    private static final String MESSAGINGSERVICE_URL = "https://client-s.gateway.messenger.live.com/v1/users/ME/endpoints/%s/presenceDocs/messagingService";
    private static final String LOGOUT_URL = "https://login.skype.com/logout?client_id=578134&redirect_uri=https%3A%2F%2Fweb.skype.com&intsrc=client-_-webapp-_-production-_-go-signin";

    private static final Pattern TARGET_PATTERN = Pattern.compile("<target>8:([^<]*)<\\/target>");
    private static final Pattern ID_PATTERN = Pattern.compile("<id>8:([^<]*)<\\/id><role>([^<]*)<\\/role>");
    private static final Pattern VALUE_PATTERN = Pattern.compile("<value>([^<]*)<\\/value>");
    private static final Pattern URL_PATTERN = Pattern.compile("conversations\\/(.*)");
    private static final Pattern USER_PATTERN = Pattern.compile("8:(.*)");

    private final Map<String, String> cookies = new HashMap<>();
    private final String skypeToken;
    private final String registrationToken;
    private final String endpointId;
    private final String username;

    private final EventDispatcher eventDispatcher;

    private Thread sessionKeepaliveThread;
    private Thread pollThread;

    private final ExecutorService scheduler = Executors.newFixedThreadPool(16);

    private final Logger logger = Logger.getLogger("webskype");

    private final Map<String, Chat> allChats = new ConcurrentHashMap<>();

    public WebSkype(String username, String password) throws SkypeException {
        try {
            this.username = username;
            this.eventDispatcher = new SkypeEventDispatcher();
            final UUID guid = UUID.randomUUID();
            Response loginResponse = postToLogin(username, password);
            cookies.putAll(loginResponse.cookies());
            Document loginResponseDocument = loginResponse.parse();
            Elements inputs = loginResponseDocument.select("input[name=skypetoken]");
            if (inputs.size() > 0) {
                skypeToken = inputs.get(0).attr("value");
                sessionKeepaliveThread = new Thread(String.format("Skype-%s-Session", username)) {
                    public void run() {
                        while (true) {
                            try {
                                Jsoup.connect(PING_URL).header("X-Skypetoken", skypeToken).cookies(cookies).data("sessionId", guid.toString()).post();
                                Thread.sleep(300000);
                            } catch (IOException | InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                };
                sessionKeepaliveThread.start();

                Response getAsm = Jsoup.connect("https://api.asm.skype.com/v1/skypetokenauth").cookies(cookies).data("skypetoken", skypeToken).method(Method.POST).execute();
                cookies.putAll(getAsm.cookies());
                HttpsURLConnection getReg = (HttpsURLConnection) new URL("https://client-s.gateway.messenger.live.com/v1/users/ME/endpoints").openConnection();
                getReg.setRequestProperty("Authentication", "skypetoken=" + skypeToken);
                getReg.setRequestMethod("POST");
                getReg.setDoOutput(true);
                getReg.getOutputStream().write("{}".getBytes());
                getReg.getInputStream();
                String[] splits = getReg.getHeaderField("Set-RegistrationToken").split(";");
                registrationToken = splits[0];
                endpointId = splits[2].split("=")[1];

                Date now = new Date();
                now.setDate(now.getDate() - 14);

                Gson gson = new Gson();
                String urlToUse = "https://client-s.gateway.messenger.live.com/v1/users/ME/conversations?startTime=" + now.getTime() + "&pageSize=100&view=msnp24Equivalent&targetType=Passport|Skype|Lync|Thread";
                //        while (true) {
                try {
                    URL url = new URL(urlToUse);
                    HttpsURLConnection con = (HttpsURLConnection) url.openConnection();
                    con.setRequestProperty("RegistrationToken", registrationToken);
                    String in = StreamUtils.readFully(con.getInputStream());
                    JsonObject obj = gson.fromJson(in, JsonObject.class);

                    for (JsonElement elem : obj.get("conversations").getAsJsonArray()) {
                        try {
                            JsonObject conversation = elem.getAsJsonObject();
                            String id = conversation.get("id").getAsString();
                            Chat chat = WebChat.createChat(this, id);
                            chat.updateUsers();
                            allChats.put(id, chat);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                    //                if (obj.get("_metadata").getAsJsonObject().has("backwardLink")) {
                    //                    urlToUse = obj.get("_metadata").getAsJsonObject().get("backwardLink").getAsString();
                    //                    System.out.println("Backwards");
                    //                } else {
                    //                    break;
                    //                }
                } catch (Exception e) {
                    throw new SkypeException("An exception occured while fetching chats", e);
                }
                //        }
            } else {
                throw new SkypeException("Login failure");
            }
        } catch (IOException e) {
            throw new SkypeException("An exception occured while logging in", e);
        }
    }

    public void subscribe() throws IOException {
        Gson gson = new Gson();
        
        HttpsURLConnection subscribe = (HttpsURLConnection) new URL(SUBSCRIPTIONS_URL).openConnection();
        subscribe.setRequestMethod("POST");
        subscribe.setDoOutput(true);
        subscribe.setRequestProperty("RegistrationToken", registrationToken);
        subscribe.setRequestProperty("Content-Type", "application/json");
        subscribe.getOutputStream().write(gson.toJson(buildSubscriptionObject()).getBytes());
        subscribe.getInputStream();

        HttpsURLConnection registerEndpoint = (HttpsURLConnection) new URL(String.format(MESSAGINGSERVICE_URL, URLEncoder.encode(endpointId, "UTF-8"))).openConnection();
        registerEndpoint.setRequestMethod("PUT");
        registerEndpoint.setDoOutput(true);
        registerEndpoint.setRequestProperty("RegistrationToken", registrationToken);
        registerEndpoint.setRequestProperty("Content-Type", "application/json");
        registerEndpoint.getOutputStream().write(gson.toJson(buildRegistrationObject()).getBytes());
        registerEndpoint.getInputStream();
        
        pollThread = new Thread() {
            public void run() {
                try {
                    URL url = new URL("https://client-s.gateway.messenger.live.com/v1/users/ME/endpoints/SELF/subscriptions/0/poll");
                    Gson gson = new Gson();
                    HttpsURLConnection c = null;
                    while (true) {
                        try {
                            c = (HttpsURLConnection) url.openConnection();
                            c.setRequestMethod("POST");
                            c.setDoOutput(true);
                            c.addRequestProperty("Content-Type", "application/json");
                            c.addRequestProperty("RegistrationToken", registrationToken);
                            c.getOutputStream().write(new byte[0]);
                            InputStream read = c.getInputStream();
                            String json = StreamUtils.readFully(read);
                            if (!json.isEmpty()) {
                                final JsonObject message = gson.fromJson(json, JsonObject.class);
                                scheduler.execute(new Runnable() {
                                    public void run() {
                                        if (message.has("eventMessages")) {
                                            JsonArray arr = message.get("eventMessages").getAsJsonArray();
                                            for (JsonElement event : arr) {
                                                JsonObject eventObj = event.getAsJsonObject();
                                                String resourceType = eventObj.get("resourceType").getAsString();
                                                if (eventObj.get("resourceType").getAsString().equals("NewMessage")) {
                                                    JsonObject resource = eventObj.get("resource").getAsJsonObject();
                                                    String messageType = resource.get("messagetype").getAsString();
                                                    if (resource.get("messagetype").getAsString().equals("RichText") || resource.get("messagetype").getAsString().equals("Text")) {
                                                        try {
                                                            String clientid = resource.has("clientmessageid") ? resource.get("clientmessageid").getAsString() : resource.get("skypeeditedid").getAsString();
                                                            String id = resource.get("id").getAsString();
                                                            String from = getUser(resource.get("from").getAsString());
                                                            String url = resource.get("conversationLink").getAsString();
                                                            Chat c = fromUrl(url);
                                                            ChatMessage m = WebChatMessage.createMessage(c, c.getUser(from), id, clientid, System.currentTimeMillis(), resource.get("content").getAsString());
                                                            MessageReceivedEvent evnt = new MessageReceivedEvent(m);
                                                            eventDispatcher.callEvent(evnt);
                                                        } catch (NullPointerException e) {
                                                            logger.log(Level.SEVERE, "An NPE occured while parsing a message");
                                                            logger.log(Level.SEVERE, message.toString());
                                                        }
                                                    } else if (resource.get("messagetype").getAsString().equals("ThreadActivity/AddMember")) {
                                                        String content = resource.get("content").getAsString();
                                                        Matcher m = TARGET_PATTERN.matcher(content);
                                                        m.find();
                                                        String target = m.group(1);
                                                        String url = resource.get("conversationLink").getAsString();
                                                        Chat c = fromUrl(url);
                                                        ((WebChat) c).addUser(target);
                                                        UserAddEvent e = new UserAddEvent(c.getUser(target));
                                                        eventDispatcher.callEvent(e);
                                                    } else if (resource.get("messagetype").getAsString().equals("ThreadActivity/DeleteMember")) {
                                                        String content = resource.get("content").getAsString();
                                                        Matcher m = TARGET_PATTERN.matcher(content);
                                                        m.find();
                                                        String target = m.group(1);
                                                        String url = resource.get("conversationLink").getAsString();
                                                        Chat c = fromUrl(url);
                                                        ((WebChat) c).removeUser(target);
                                                        UserRemoveEvent e = new UserRemoveEvent(c.getUser(target));
                                                        eventDispatcher.callEvent(e);
                                                    } else if (resource.get("messagetype").getAsString().equals("ThreadActivity/RoleUpdate")) {
                                                        String content = resource.get("content").getAsString();
                                                        Matcher m = ID_PATTERN.matcher(content);
                                                        m.find();
                                                        String target = m.group(1);
                                                        String role = m.group(2);
                                                        String url = resource.get("conversationLink").getAsString();
                                                        Chat c = fromUrl(url);
                                                        ((WebChat) c).getUser(target).setRole(role.equals("admin") ? Role.ADMIN : Role.USER);
                                                        RoleUpdateEvent e = new RoleUpdateEvent(c.getUser(target));
                                                        eventDispatcher.callEvent(e);
                                                    } else if (messageType.equalsIgnoreCase("Control/Typing")) {
                                                    } else if (messageType.equalsIgnoreCase("Control/ClearTyping")) {
                                                    } else if (messageType.equalsIgnoreCase("Control/LiveState")) {
                                                    } else if (messageType.equalsIgnoreCase("ThreadActivity/TopicUpdate")) {
                                                        String content = resource.get("content").getAsString();
                                                        Matcher m = VALUE_PATTERN.matcher(content);
                                                        String url = resource.get("conversationLink").getAsString();
                                                        Chat c = fromUrl(url);
                                                        if (c instanceof WebChatGroup) {
                                                            if (m.find()) {
                                                                ((WebChatGroup) c).updateTopic(StringEscapeUtils.unescapeHtml4(m.group(1)));
                                                            } else {
                                                                ((WebChatGroup) c).updateTopic("");
                                                            }
                                                            TopicChangeEvent e = new TopicChangeEvent(c);
                                                            eventDispatcher.callEvent(e);
                                                        }
                                                    } else {
                                                        logger.severe("Unhandled messageType " + messageType);
                                                        logger.severe(eventObj.toString());
                                                    }
                                                } else if (resourceType.equalsIgnoreCase("EndpointPresence")) {
                                                } else if (resourceType.equalsIgnoreCase("UserPresence")) {
                                                } else if (resourceType.equalsIgnoreCase("ConversationUpdate")) {
                                                } else if (resourceType.equalsIgnoreCase("ThreadUpdate")) {
                                                } else {
                                                    logger.severe("Unhandled resourceType " + resourceType);
                                                    logger.severe(eventObj.toString());
                                                }
                                            }
                                        }
                                    }
                                });
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        };
        pollThread.start();
    }

    @Override
    public Chat getChat(String name) {
        return allChats.get(name);
    }

    @Override
    public List<Chat> getAllChats() {
        return new ArrayList<>(this.allChats.values());
    }

    @Override
    public void logout() throws IOException {
        Jsoup.connect(LOGOUT_URL).cookies(this.cookies).get();
    }

    public String getRegistrationToken() {
        return this.registrationToken;
    }

    public String getSkypeToken() {
        return this.skypeToken;
    }

    @Override
    public EventDispatcher getEventDispatcher() {
        return this.eventDispatcher;
    }

    private Chat fromUrl(String url) {
        Matcher m = URL_PATTERN.matcher(url);
        if (m.find()) {
            return allChats.get(m.group(1));
        }
        return null;
    }

    private String getUser(String url) {
        Matcher m = USER_PATTERN.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private Response postToLogin(String username, String password) throws IOException {
        Map<String, String> data = new HashMap<>();
        Document loginDocument = Jsoup.connect(LOGIN_URL).get();
        Element loginForm = loginDocument.getElementById("loginForm");
        for (Element input : loginForm.getElementsByTag("input")) {
            data.put(input.attr("name"), input.attr("value"));
        }
        Date now = new Date();
        data.put("timezone_field", new SimpleDateFormat("XXX").format(now).replace(':', '|'));
        data.put("username", username);
        data.put("password", password);
        data.put("js_time", String.valueOf(now.getTime() / 1000));
        return Jsoup.connect(LOGIN_URL).data(data).method(Method.POST).execute();
    }

    private JsonObject buildSubscriptionObject() {
        JsonObject subscriptionObject = new JsonObject();
        subscriptionObject.addProperty("channelType", "httpLongPoll");
        subscriptionObject.addProperty("template", "raw");
        JsonArray interestedResources = new JsonArray();
        interestedResources.add(new JsonPrimitive("/v1/users/ME/conversations/ALL/properties"));
        interestedResources.add(new JsonPrimitive("/v1/users/ME/conversations/ALL/messages"));
        interestedResources.add(new JsonPrimitive("/v1/users/ME/contacts/ALL"));
        interestedResources.add(new JsonPrimitive("/v1/threads/ALL"));
        subscriptionObject.add("interestedResources", interestedResources);
        return subscriptionObject;
    }
    
    private JsonObject buildRegistrationObject() {
        JsonObject registrationObject = new JsonObject();
        registrationObject.addProperty("id", "messagingService");
        registrationObject.addProperty("type", "EndpointPresenceDoc");
        registrationObject.addProperty("selfLink", "uri");
        JsonObject publicInfo = new JsonObject();
        publicInfo.addProperty("capabilities", "video|audio");
        publicInfo.addProperty("type", 1);
        publicInfo.addProperty("skypeNameVersion", "908/1.5.116/swx-skype.com");
        publicInfo.addProperty("nodeInfo", "xx");
        publicInfo.addProperty("version", "908/1.5.116");
        JsonObject privateInfo = new JsonObject();
        privateInfo.addProperty("epname", "Skype4J");
        registrationObject.add("publicInfo", publicInfo);
        registrationObject.add("privateInfo", privateInfo);
        return registrationObject;
    }

    @Override
    public String getUsername() {
        return this.username;
    }

}