/*
 * Copyright (C) 2005-2007 Alfresco Software Limited.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 * As a special exception to the terms and conditions of version 2.0 of
 * the GPL, you may redistribute this Program in connection with Free/Libre
 * and Open Source Software ("FLOSS") applications as described in Alfresco's
 * FLOSS exception.  You should have recieved a copy of the text describing
 * the FLOSS exception, and it is also available here:
 * http://www.alfresco.com/legal/licensing"
 */
package org.alfresco.filesys.auth.nfs;

import java.util.HashMap;
import java.util.List;

import javax.transaction.Status;
import javax.transaction.UserTransaction;

import org.alfresco.config.ConfigElement;
import org.alfresco.filesys.AlfrescoConfigSection;
import org.alfresco.filesys.alfresco.AlfrescoClientInfo;
import org.alfresco.jlan.oncrpc.AuthType;
import org.alfresco.jlan.oncrpc.Rpc;
import org.alfresco.jlan.oncrpc.RpcAuthenticationException;
import org.alfresco.jlan.oncrpc.RpcAuthenticator;
import org.alfresco.jlan.oncrpc.RpcPacket;
import org.alfresco.jlan.oncrpc.nfs.NFS;
import org.alfresco.jlan.server.SrvSession;
import org.alfresco.jlan.server.auth.ClientInfo;
import org.alfresco.jlan.server.config.InvalidConfigurationException;
import org.alfresco.jlan.server.config.ServerConfiguration;
import org.alfresco.service.transaction.TransactionService;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Alfresco RPC Authenticator Class
 * 
 * <p>Provides authentication support for the NFS server.
 * 
 * @author gkspencer
 */
public class AlfrescoRpcAuthenticator implements RpcAuthenticator {

	// Debug logging

	private static final Log logger = LogFactory.getLog("org.alfresco.nfs.protocol.auth");

	// Authentication types aupported by this implementation

	private int[] _authTypes = { AuthType.Unix };

	// UID/GID to username conversions
	
	private HashMap<Integer, String> m_idMap;
	
  // Alfresco configuration
  
  protected AlfrescoConfigSection m_alfrescoConfig;
	
	/**
	 * Authenticate an RPC client and create a unique session id key.
	 * 
	 * @param authType int
	 * @param rpc RpcPacket
	 * @return Object
	 * @throws RpcAuthenticationException
	 */
	public Object authenticateRpcClient(int authType, RpcPacket rpc)
			throws RpcAuthenticationException {

		// Create a unique session key depending on the authentication type

		Object sessKey = null;

		if (authType == AuthType.Unix) {

			// Get the gid and uid from the credentials data in the request

			rpc.positionAtCredentialsData();
			rpc.skipBytes(4);
			int nameLen = rpc.unpackInt();
			rpc.skipBytes(nameLen);

			int uid = rpc.unpackInt();
			int gid = rpc.unpackInt();

			// DEBUG
			
			if ( logger.isDebugEnabled())
				logger.debug( "RpcAuth: Type=Unix uid=" + uid + ", gid=" + gid);
			
			// Check that there is a user name mapping for the uid/gid
			
			Integer idKey = new Integer((gid << 16) + uid);
			String userName = m_idMap.get( idKey);
			
			if ( userName == null)
				throw new RpcAuthenticationException( NFS.StsAccess);
			
			// Check if the Unix authentication session table is valid

			sessKey = new Long((((long) rpc.getClientAddress().hashCode()) << 32) + (gid << 16) + uid);
		}
		else if ( authType == AuthType.Null)
		{
			// Set the session key for the null authentication
			
			sessKey = new Integer(rpc.getClientAddress().hashCode());

			// DEBUG
			
			if ( logger.isDebugEnabled())
				logger.debug( "RpcAuth: Type=Null client=" + rpc.getClientAddress());
		}

		// Check if the session key is valid, if not then the authentication
		// type is unsupported

		if (sessKey == null)
			throw new RpcAuthenticationException(Rpc.AuthBadCred, "Unsupported auth type, " + authType);

		// DEBUG

		if (logger.isDebugEnabled())
			logger.debug("RpcAuth: RPC from " + rpc.getClientDetails()
					+ ", authType=" + AuthType.getTypeAsString(authType)
					+ ", sessKey=" + sessKey);

		// Return the session key

		return sessKey;
	}

	/**
	 * Return the authentication types that are supported by this
	 * implementation.
	 * 
	 * @return int[]
	 */
	public int[] getRpcAuthenticationTypes() {
		return _authTypes;
	}

	/**
	 * Return the client information for the specified RPC request
	 * 
	 * @param sessKey Object
	 * @param rpc RpcPacket
	 * @return ClientInfo
	 */
	public ClientInfo getRpcClientInformation(Object sessKey, RpcPacket rpc)
	{
		// Create a client information object to hold the client details

		ClientInfo cInfo = null;

		// Get the authentication type

		int authType = rpc.getCredentialsType();

		// Unpack the client details from the RPC request

		if ( authType == AuthType.Unix) {

			// Unpack the credentials data

			rpc.positionAtCredentialsData();
			rpc.skipBytes(4); // stamp id

			String clientAddr = rpc.unpackString();
			int uid = rpc.unpackInt();
			int gid = rpc.unpackInt();

			// Check for an additional groups list

			int grpLen = rpc.unpackInt();
			int[] groups = null;
			
			if (grpLen > 0) {
				groups = new int[grpLen];
				rpc.unpackIntArray(groups);
			}

			// Get the user name mapping for the uid/gid and authenticate
			
			Integer idKey = new Integer((gid << 16) + uid);
			String userName = m_idMap.get( idKey);

			// DEBUG
			
			if ( logger.isDebugEnabled())
				logger.debug( "RpcClientInfo: username=" + userName + ", uid=" + uid + ", gid=" + gid);
			
			// Create the client information if there is a valid mapping

			if ( userName != null)
			{
				// Create the client information and fill in relevant fields
				
				cInfo = ClientInfo.getFactory().createInfo( userName, null);
				
				cInfo.setNFSAuthenticationType( authType);
				cInfo.setClientAddress( clientAddr);
				cInfo.setUid( uid);
				cInfo.setGid( gid);

				cInfo.setGroupsList(groups);
			}
			
			// DEBUG
			
			if (logger.isDebugEnabled())
				logger.debug("RpcAuth: Client info, type=" + AuthType.getTypeAsString(authType) + ", name="
						+ clientAddr + ", uid=" + uid + ", gid=" + gid + ", groups=" + grpLen);
		}
		else if ( authType == AuthType.Null)
		{
			// Create the client information
			
			cInfo = ClientInfo.getFactory().createInfo( "", null);
			cInfo.setClientAddress(rpc.getClientAddress().getHostAddress());

			// DEBUG

			if (logger.isDebugEnabled())
				logger.debug("RpcAuth: Client info, type=" + AuthType.getTypeAsString(authType) + ", addr="
						+ rpc.getClientAddress().getHostAddress());
		}

		// Return the client information

		return cInfo;
	}

    /**
     * Set the current authenticated user context for this thread
     * 
     * @param sess SrvSession
     * @param client ClientInfo
     */
    public void setCurrentUser( SrvSession sess, ClientInfo client)
    {
    	// Start a transaction
    	
      UserTransaction tx = createTransaction(); 

      try
      {
        // start the transaction
        
        tx.begin();

      	// Check the account type and setup the authentication context
      	
      	if ( client == null || client.isNullSession() || client instanceof AlfrescoClientInfo == false)
      	{
      		// Clear the authentication, null user should not be allowed to do any service calls
      		
      		m_alfrescoConfig.getAuthenticationComponent().clearCurrentSecurityContext();
      		
      		// DEBUG
      		
      		if ( logger.isDebugEnabled())
      			logger.debug("Clear security context, client=" + client);
      	}
      	else if ( client.isGuest() == false)
      	{
      	  // Access the Alfresco client
      	  
      	  AlfrescoClientInfo alfClient = (AlfrescoClientInfo) client;
      	  
      		// Check if the authentication token has been set for the client
      		
      		if ( alfClient.hasAuthenticationToken() == false)
      		{
      			// Set the current user and retrieve the authentication token
      			
      			m_alfrescoConfig.getAuthenticationComponent().setCurrentUser( client.getUserName());
      			alfClient.setAuthenticationToken( m_alfrescoConfig.getAuthenticationComponent().getCurrentAuthentication());
      			
      			// DEBUG
      			
      			if ( logger.isDebugEnabled())
      				logger.debug("Set user name=" + client.getUserName() + ", token=" + alfClient.getAuthenticationToken());
      		}
      		else
      		{
  	    		// Set the authentication context for the request
  	    		
      		  m_alfrescoConfig.getAuthenticationComponent().setCurrentAuthentication( alfClient.getAuthenticationToken());
      			
      			// DEBUG
      			
      			if ( logger.isDebugEnabled())
      				logger.debug("Set user using auth token, token=" + alfClient.getAuthenticationToken());
      		}
      	}
      	else
      	{
      		// Enable guest access for the request
      		
      	  m_alfrescoConfig.getAuthenticationComponent().setGuestUserAsCurrentUser();
  			
      	  // DEBUG
  			
      	  if ( logger.isDebugEnabled())
      	    logger.debug("Set guest user");
      	}
      }
      catch ( Exception ex)
      {
        if ( logger.isDebugEnabled())
          logger.debug( ex);
      }
      finally
      {
        // Commit the transaction
        
        if ( tx != null)
        {
            try
            {
                // Commit or rollback the transaction
                
                if ( tx.getStatus() == Status.STATUS_MARKED_ROLLBACK)
                {
                    // Transaction is marked for rollback
                    
                    tx.rollback();
                }
                else
                {
                    // Commit the transaction
                    
                    tx.commit();
                }
            }
            catch ( Exception ex)
            {
            }
        }
      }
    }
    
	/**
	 * Initialize the RPC authenticator
	 * 
	 * @param config ServerConfiguration
	 * @param params NameValueList
	 * @throws InvalidConfigurationException
	 */
	public void initialize(ServerConfiguration config, ConfigElement params)
		throws InvalidConfigurationException {

		// Get the Alfresco configuration section, for access to services

	  m_alfrescoConfig = (AlfrescoConfigSection) config.getConfigSection( AlfrescoConfigSection.SectionName);
		
		// Check for the user mappings
		
		ConfigElement userMappings = params.getChild("userMappings");
		if ( userMappings != null)
		{
			// Allocate the id mappings table
			
			m_idMap = new HashMap<Integer, String>();
			
			// Get the user map elements
			
			List<ConfigElement> userMaps = userMappings.getChildren();
			
			// Process the user list
			
			for ( ConfigElement userElem : userMaps)
			{
				// Validate the element type
				
				if ( userElem.getName().equalsIgnoreCase( "user"))
				{
					// Get the user name, user id and group id
					
					String userName = userElem.getAttribute("name");
					String uidStr   = userElem.getAttribute("uid");
					String gidStr   = userElem.getAttribute("gid");
					
					if ( userName == null || userName.length() == 0)
						throw new InvalidConfigurationException("Empty user name, or name not specified");
					
					if ( uidStr == null || uidStr.length() == 0)
						throw new InvalidConfigurationException("Invalid uid, or uid not specified, for user " + userName);
					
					if ( gidStr == null || gidStr.length() == 0)
						throw new InvalidConfigurationException("Invalid gid, or gid not specified, for user " + userName);
					
					// Parse the uid/gid
					
					int uid = -1;
					int gid = -1;
					
					try
					{
						uid = Integer.parseInt( uidStr);
					}
					catch ( NumberFormatException ex)
					{
						throw new InvalidConfigurationException("Invalid uid value, " + uidStr + " for user " + userName);
					}
					
					try
					{
						gid = Integer.parseInt( gidStr);
					}
					catch ( NumberFormatException ex)
					{
						throw new InvalidConfigurationException("Invalid gid value, " + gidStr + " for user " + userName);
					}
					
					// Check if the mapping already exists
					
					Integer idKey = new Integer(( gid << 16) + uid);
					if ( m_idMap.containsKey( idKey) == false)
					{
						// Add the username uid/gid mapping
						
						m_idMap.put( idKey, userName);
						
						// DEBUG
						
						if ( logger.isDebugEnabled())
							logger.debug("Added RPC user mapping for user " + userName + " uid=" + uid + ", gid=" + gid);
					}
					else if ( logger.isDebugEnabled())
					{
						// DEBUG
						
						logger.debug("Ignored duplicate mapping for uid=" + uid + ", gid=" + gid);
					}
				}
				else
					throw new InvalidConfigurationException( "Invalid user mapping, " + userElem.getName());
			}
		}
		
		// Make sure there are some user mappings
		
		if ( m_idMap == null || m_idMap.size() == 0)
			throw new InvalidConfigurationException("No user mappings for RPC authenticator");
	}
	
	/**
	 * Create a transaction, this will be a wrteable transaction unless the system is in read-only mode.
	 * 
	 * return UserTransaction
	 */
	protected final UserTransaction createTransaction()
	{
		// Get the transaction service
		
		TransactionService txService = m_alfrescoConfig.getTransactionService(); 
		
		// DEBUG
		
		if ( logger.isDebugEnabled())
			logger.debug("Using " + (txService.isReadOnly() ? "ReadOnly" : "Write") + " transaction");
		
		// Create the transaction
		
		return txService.getUserTransaction( txService.isReadOnly() ? true : false);
	}
}
