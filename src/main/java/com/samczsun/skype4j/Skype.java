package com.samczsun.skype4j;

import com.samczsun.skype4j.chat.Chat;
import com.samczsun.skype4j.events.EventDispatcher;
import com.samczsun.skype4j.exceptions.ConnectionException;
import com.samczsun.skype4j.exceptions.InvalidCredentialsException;
import com.samczsun.skype4j.exceptions.ParseException;

import java.io.IOException;
import java.util.Collection;
import java.util.logging.Logger;

public abstract class Skype {

    /**
     * Subscribe to the HTTP long polling service. This will start reading
     * events from Skype and calling events within this API
     *
     * @throws IOException Thrown if any internal operations go wrong
     */
    public abstract void subscribe() throws IOException;

    /**
     * Get the username of the account logged in
     *
     * @return The username
     */
    public abstract String getUsername();

    /**
     * Get a chat based on the identity given. If no chat is found, null will be returned
     *
     * @param name The identity of the chat
     * @return The {@link Chat Chat} object, or null if not found
     */
    public abstract Chat getChat(String name);

    /**
     * Load a chat given an identity. No attempts will be made to validate whether the chat exists
     */
    public abstract Chat loadChat(String name) throws ConnectionException ;

    /**
     * Get all the chats loaded by this API
     *
     * @return A view of all the chats
     */
    public abstract Collection<Chat> getAllChats();

    /**
     * Log into Skype
     *
     * @throws InvalidCredentialsException If you've provided invalid credentials or if you hit a CAPTCHA
     * @throws ConnectionException If a network error occured while connecting
     * @throws ParseException If invalid HTML/XML was returned, causing Jsoup to raise an exception
     */
    public abstract void login() throws InvalidCredentialsException, ConnectionException, ParseException;

    /**
     * Log out and stop all threads
     *
     * @throws IOException
     */
    public abstract void logout() throws ConnectionException;

    /**
     * Get the event dispatcher that handles listening to events
     *
     * @return The {@link EventDispatcher EventDispatcher}
     */
    public abstract EventDispatcher getEventDispatcher();

    /**
     * Get the Logger used for debugging
     *
     * @return The Logger
     */
    public abstract Logger getLogger();
}
