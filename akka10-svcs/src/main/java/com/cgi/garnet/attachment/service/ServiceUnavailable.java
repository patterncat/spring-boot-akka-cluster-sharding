package com.cgi.garnet.attachment.service;

/**
 * Created by davenkat on 10/25/2015.
 */
public class ServiceUnavailable extends RuntimeException {

    public ServiceUnavailable(String msg) {
        super(msg);
    }
}
