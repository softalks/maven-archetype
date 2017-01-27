package org.apache.maven.archetype.source;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.maven.archetype.catalog.Archetype;
import org.apache.maven.archetype.catalog.ArchetypeCatalog;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.LegacySupport;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.apache.maven.wagon.UnsupportedProtocolException;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.WagonException;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.proxy.ProxyInfo;
import org.apache.maven.wagon.repository.Repository;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.util.ReaderFactory;

/**
 * @author Jason van Zyl
 */
@Component( role = ArchetypeDataSource.class, hint = "remote-catalog" )
public class RemoteCatalogArchetypeDataSource
    extends CatalogArchetypeDataSource
{
    @Requirement
    private Map<String, Wagon> wagons;
    
    @Requirement
    private LegacySupport legacySupport;

    @Requirement
    private SettingsDecrypter settingsDecrypter;

    public static final String REPOSITORY_PROPERTY = "repository";

    /**
     * Id of the repository used to download catalog file. Proxy or authentication info can
     * be setup in settings.xml.
     */
    public static final String REPOSITORY_ID = "archetype";

    public ArchetypeCatalog getArchetypeCatalog( Properties properties )
        throws ArchetypeDataSourceException
    {
        String repository = properties.getProperty( REPOSITORY_PROPERTY );

        if ( repository == null )
        {
            throw new ArchetypeDataSourceException( "To use the remote catalog you must specify the 'repository'"
                + " property with an URL." );
        }

        if ( repository.endsWith( "/" ) )
        {
            repository = repository.substring( 0, repository.length() - 1 );
        }

        try
        {
            return downloadCatalog( repository, ARCHETYPE_CATALOG_FILENAME );
        }
        catch ( ArchetypeDataSourceException e )
        {
            throw e;
        }
        catch ( Exception e )
        { // When the default archetype catalog name doesn't work, we assume the repository is the URL to a file
            String repositoryPath = repository.substring( 0, repository.lastIndexOf( "/" ) );
            String filename = repository.substring( repository.lastIndexOf( "/" ) + 1 );

            try
            {
                return downloadCatalog( repositoryPath, filename );
            }
            catch ( Exception ex )
            {
                getLogger().warn( "Error reading archetype catalog " + repository, ex );
                return new ArchetypeCatalog();
            }
        }
    }

    public void updateCatalog( Properties properties, Archetype archetype )
        throws ArchetypeDataSourceException
    {
        throw new ArchetypeDataSourceException( "Not supported yet." );
    }

    private ArchetypeCatalog downloadCatalog( String repositoryPath, String filename )
        throws WagonException, IOException, ArchetypeDataSourceException
    {
        getLogger().debug( "Searching for remote catalog: " + repositoryPath + "/" + filename );

        // We use wagon to take advantage of a Proxy that has already been setup in a Maven environment.
        Repository wagonRepository = new Repository( REPOSITORY_ID, repositoryPath );
        AuthenticationInfo authInfo = getAuthenticationInfo( wagonRepository.getId() );
        ProxyInfo proxyInfo = getProxy( wagonRepository.getProtocol() );

        Wagon wagon = getWagon( wagonRepository );

        File catalog = File.createTempFile( "archetype-catalog", ".xml" );
        try
        {
            wagon.connect( wagonRepository, authInfo, proxyInfo );
            wagon.get( filename, catalog );

            return readCatalog( ReaderFactory.newXmlReader( catalog ) );
        }
        finally
        {
            disconnectWagon( wagon );
            catalog.delete();
        }
    }

    private void disconnectWagon( Wagon wagon )
    {
        try
        {
            wagon.disconnect();
        }
        catch ( Exception e )
        {
            getLogger().warn( "Problem disconnecting from wagon - ignoring: " + e.getMessage() );
        }
    }

    // 
    
    private Wagon getWagon( Repository repository )
        throws UnsupportedProtocolException
    {
        return getWagon( repository.getProtocol() );
    }

    private Wagon getWagon( String protocol )
        throws UnsupportedProtocolException
    {
        if ( protocol == null )
        {
            throw new UnsupportedProtocolException( "Unspecified protocol" );
        }

        String hint = protocol.toLowerCase( java.util.Locale.ENGLISH );

        Wagon wagon = wagons.get( hint );
        if ( wagon == null )
        {
            throw new UnsupportedProtocolException( "Cannot find wagon which supports the requested protocol: "
                + protocol );
        }

        return wagon;
    }
    
    private AuthenticationInfo getAuthenticationInfo( String id )
    {
        MavenSession session = legacySupport.getSession();

        if ( session != null && id != null )
        {
            MavenExecutionRequest request = session.getRequest();

            if ( request != null )
            {
                List<Server> servers = request.getServers();

                if ( servers != null )
                {
                    for ( Server server : servers )
                    {
                        if ( id.equalsIgnoreCase( server.getId() ) )
                        {
                            SettingsDecryptionResult result =
                                settingsDecrypter.decrypt( new DefaultSettingsDecryptionRequest( server ) );
                            server = result.getServer();

                            AuthenticationInfo authInfo = new AuthenticationInfo();
                            authInfo.setUserName( server.getUsername() );
                            authInfo.setPassword( server.getPassword() );
                            authInfo.setPrivateKey( server.getPrivateKey() );
                            authInfo.setPassphrase( server.getPassphrase() );

                            return authInfo;
                        }
                    }
                }
            }
        }

        // empty one to prevent NPE
       return new AuthenticationInfo();
    }

    private ProxyInfo getProxy( String protocol )
    {
        MavenSession session = legacySupport.getSession();

        if ( session != null && protocol != null )
        {
            MavenExecutionRequest request = session.getRequest();

            if ( request != null )
            {
                List<Proxy> proxies = request.getProxies();

                if ( proxies != null )
                {
                    for ( Proxy proxy : proxies )
                    {
                        if ( proxy.isActive() && protocol.equalsIgnoreCase( proxy.getProtocol() ) )
                        {
                            SettingsDecryptionResult result =
                                settingsDecrypter.decrypt( new DefaultSettingsDecryptionRequest( proxy ) );
                            proxy = result.getProxy();

                            ProxyInfo proxyInfo = new ProxyInfo();
                            proxyInfo.setHost( proxy.getHost() );
                            proxyInfo.setType( proxy.getProtocol() );
                            proxyInfo.setPort( proxy.getPort() );
                            proxyInfo.setNonProxyHosts( proxy.getNonProxyHosts() );
                            proxyInfo.setUserName( proxy.getUsername() );
                            proxyInfo.setPassword( proxy.getPassword() );

                            return proxyInfo;
                        }
                    }
                }
            }
        }

        return null;
    }

}
