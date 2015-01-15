/*
 *   <copyright 
 *   notice="oco-source" 
 *   pids="5725-P60" 
 *   years="2015" 
 *   crc="1438874957" > 
 *   IBM Confidential 
 *    
 *   OCO Source Materials 
 *    
 *   5724-H72
 *    
 *   (C) Copyright IBM Corp. 2015
 *    
 *   The source code for the program is not published 
 *   or otherwise divested of its trade secrets, 
 *   irrespective of what has been deposited with the 
 *   U.S. Copyright Office. 
 *   </copyright> 
 */

package com.ibm.mqlight.api.impl.network;

import com.ibm.mqlight.api.impl.Message;
import com.ibm.mqlight.api.network.NetworkChannel;

public class ConnectResponse extends Message {
    public final NetworkChannel channel;
    public final Throwable cause;
    public final Object context;
    
    public ConnectResponse(NetworkChannel channel, Throwable cause, Object context) {
        this.channel = channel;
        this.cause = cause;
        this.context = context;
    }
}
