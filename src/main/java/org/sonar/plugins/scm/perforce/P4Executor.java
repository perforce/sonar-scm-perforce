/*
 * SonarQube :: Plugins :: SCM :: Perforce
 * Copyright (C) 2014 SonarSource
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.plugins.scm.perforce;

import com.perforce.p4java.client.IClient;
import com.perforce.p4java.client.IClientViewMapping;
import com.perforce.p4java.core.IChangelist;
import com.perforce.p4java.core.file.FileSpecBuilder;
import com.perforce.p4java.core.file.IFileSpec;
import com.perforce.p4java.exception.MessageSeverityCode;
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.impl.generic.client.ClientView;
import com.perforce.p4java.impl.generic.client.ClientView.ClientViewMapping;
import com.perforce.p4java.impl.mapbased.rpc.sys.helper.RpcSystemFileCommandsHelper;
import com.perforce.p4java.option.UsageOptions;
import com.perforce.p4java.option.server.LoginOptions;
import com.perforce.p4java.option.server.TrustOptions;
import com.perforce.p4java.server.IOptionsServer;
import com.perforce.p4java.server.ServerFactory;
import com.perforce.p4java.server.callback.ICommandCallback;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.utils.MessageException;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Properties;

public class P4Executor {

  private static final Logger LOG = LoggerFactory.getLogger(P4Executor.class);

  /** Perforce server. */
  private IOptionsServer server;

  /** Perforce client. */
  private IClient client;

  /** Perforce server properties. */
  private Properties p4ServerProperties;

  /** Perforce server options. */
  private UsageOptions p4ServerOptions;

  /** The p4 login options. */
  private LoginOptions p4LoginOptions;

  private final PerforceConfiguration config;

  /**
   * Instantiates a new p4 command helper.
   *
   * @param repository
   *            the repository
   * @param fileSet
   *            the file set
   * @param logger
   *            the logger
   * @throws ScmException
   *             the scm exception
   */
  public P4Executor(PerforceConfiguration config, File workDir) {
    this.config = config;
    initP4(workDir);
  }

  /**
   * Gets the server.
   *
   * @return the server
   */
  public IOptionsServer getServer() {
    return server;
  }

  /**
   * Gets the client.
   *
   * @return the client
   */
  public IClient getClient() {
    return client;
  }

  /**
   * Sets the client.
   *
   * @param client
   *            the new client
   */
  public void setClient(IClient client) {
    this.client = client;
  }

  /**
   * Gets the p4 server properties.
   *
   * @return the p4 server properties
   */
  public Properties getP4ServerProperties() {
    return p4ServerProperties;
  }

  /**
   * Sets the p4 server properties.
   *
   * @param p4ServerProperties
   *            the new p4 server properties
   */
  public void setP4ServerProperties(Properties p4ServerProperties) {
    this.p4ServerProperties = p4ServerProperties;
  }

  /**
   * Gets the p4 server options.
   *
   * @return the p4 server options
   */
  public UsageOptions getP4ServerOptions() {
    return p4ServerOptions;
  }

  /**
   * Sets the p4 server options.
   *
   * @param p4ServerOptions
   *            the new p4 server options
   */
  public void setP4ServerOptions(UsageOptions p4ServerOptions) {
    this.p4ServerOptions = p4ServerOptions;
  }

  /**
   * Gets the p4 login options.
   *
   * @return the p4 login options
   */
  public LoginOptions getP4LoginOptions() {
    return p4LoginOptions;
  }

  /**
   * Sets the p4 login options.
   *
   * @param p4LoginOptions
   *            the new p4 login options
   */
  public void setP4LoginOptions(LoginOptions p4LoginOptions) {
    this.p4LoginOptions = p4LoginOptions;
  }

  /**
   * Initialize Perforce server and client instances.
   *
   */
  protected void initP4(File workDir) {
    // Initialize the Perforce server.
    initServer();
    // Initialize the Perforce client.
    initClient(workDir);
  }

  /**
   * Cleanup Perforce server and client instances; logout, disconnect, etc.
   *
   */
  public void cleanP4() {
    // Cleanup the Perforce server.
    cleanServer();
  }

  /**
   * Initialize an instance of the Perforce server from the factory using the
   * specified protocol, server port, protocol specific properties and usage
   * options. Register callback on the server. Connect to server; set the user
   * (if present) to server and login to the server with the user's password
   * (if present).
   *
   */
  protected void initServer() {

    try {
      // Set default system file helper
      ServerFactory.setRpcFileSystemHelper(new RpcSystemFileCommandsHelper());
      // Get an instance of the P4J server.
      if (StringUtils.isEmpty(config.port())) {
        throw MessageException.of("Please configure perforce port using " + PerforceConfiguration.PORT_PROP_KEY);
      }
      if (config.useSsl()) {
        server = ServerFactory.getOptionsServer("p4javassl://" + config.port(), p4ServerProperties, p4ServerOptions);
        server.addTrust(new TrustOptions().setAutoAccept(true));
      } else {
        server = ServerFactory.getOptionsServer("p4java://" + config.port(), p4ServerProperties, p4ServerOptions);
      }
      // Register server callback.
      server.registerCallback(new ICommandCallback() {
        public void receivedServerMessage(int key, int genericCode,
          int severityCode, String message) {
          // Log warning messages from server, since it's not included in the other callback methods.
          if (severityCode == MessageSeverityCode.E_WARN) {
            LOG.warn(message);
          }
        }

        public void receivedServerInfoLine(int key, String infoLine) {
          LOG.info(infoLine);
        }

        public void receivedServerErrorLine(int key, String errorLine) {
          LOG.error(errorLine);
        }

        public void issuingServerCommand(int key, String command) {
          LOG.info(command);
        }

        public void completedServerCommand(int key, long millisecsTaken) {
          LOG.info("Command completed in " + millisecsTaken + "ms");
        }
      });
      // Connect to the server.
      server.connect();
      // Set the Perforce charset.
      if (!isEmpty(config.charset())) {
        if (server.isConnected()) {
          if (server.supportsUnicode()) {
            server.setCharsetName(config.charset());
          }
        }
      }
      // Set server user.
      if (!isEmpty(config.username())) {
        server.setUserName(config.username());
        // Login to the server with a password.
        // Password can be null if it is not needed (i.e. SSO logins).
        server.login(config.password(), p4LoginOptions);
      }
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(e.getLocalizedMessage(), e);
    } catch (P4JavaException e) {
      throw new IllegalStateException(e.getLocalizedMessage(), e);
    }
  }

  /**
   * Cleanup the Perforce server instance. Disconnect from the Perforce
   * server. Also, set the server to null.
   *
   * Note: It does not logout, because that will delete the user's ticket.
   *
   */
  private void cleanServer() {
    try {
      server.disconnect();
      server = null;
    } catch (P4JavaException e) {
      throw new IllegalStateException(e.getLocalizedMessage(), e);
    }
  }

  /**
   * Initialize an instance of the Perforce client from the server with a
   * specified client name. Set the current client on the server.
   *
   */
  private void initClient(File workDir) {

    String rootDir = workDir.getAbsolutePath();
    try {
      rootDir = workDir.getCanonicalPath();
    } catch (IOException ex) {
    }

    rootDir = encodeWildcards(rootDir);

    try {
      // Get an instance of the Perforce client.
      String p4ClientName = config.clientName();
      if (p4ClientName == null) {
        throw MessageException.of("Please configure client (aka workspace) name using " + PerforceConfiguration.CLIENT_PROP_KEY);
      }
      // Get an instance of the Perforce client.
      client = server.getClient(p4ClientName);

      if (client == null) {
        throw new IllegalStateException("Unable to find client with name " + p4ClientName);
      }
      // Set it to the server as the current client.
      server.setCurrentClient(client);

      boolean exists = false;
      ClientViewMapping clientViewMapping = createClientViewMapping(workDir, p4ClientName);
      ClientView clientView = client.getClientView();
      if (clientView == null) {
        clientView = new ClientView();
      }
      List<IClientViewMapping> list = clientView.getEntryList();
      if (list != null) {
        for (IClientViewMapping map : list) {
          if (map.getDepotSpec().equals(clientViewMapping.getDepotSpec())
            && map.getClient().equals(clientViewMapping.getClient())) {
            exists = true;
            break;
          }
        }
      }
      if (!exists) {
        clientView.addEntry(clientViewMapping);
        client.setClientView(clientView);
        if (client.canUpdate()) {
          client.update();
        }
        if (client.canRefresh()) {
          client.refresh();
        }
      }
    } catch (P4JavaException e) {
      throw new IllegalStateException(e.getLocalizedMessage(), e);
    }
  }

  /**
   * Creates the client view mapping.
   *
   * @param repo
   *            the repo
   * @param basedir
   *            the basedir
   * @return the client view mapping
   */
  private ClientViewMapping createClientViewMapping(File basedir, String p4ClientName) {
    // Create a new client
    String repoPath = getRepoLocation(encodeWildcards(basedir.getAbsolutePath()));
    String viewPath = getCanonicalRepoPath(repoPath);
    ClientViewMapping clientViewMapping = new ClientViewMapping(0, viewPath, "//" + p4ClientName + "/...");
    return clientViewMapping;
  }

  /**
   * Gets the repo location.
   *
   * @param path
   *            the path
   * @return the repo location
   */
  private String getRepoLocation(String path) {
    String location = null;
    if (!isEmpty(path)) {
      if (client != null) {
        try {
          List<IFileSpec> fileSpecs = client.where(FileSpecBuilder.makeFileSpecList(path));
          if (fileSpecs != null) {
            for (IFileSpec fileSpec : fileSpecs) {
              if (fileSpec != null) {
                if (!isEmpty(fileSpec.getDepotPathString())) {
                  location = fileSpec.getDepotPathString();
                  break;
                }
              }
            }
          }
        } catch (P4JavaException e) {
          throw new IllegalStateException(e);
        }
      }
    }
    return location;
  }

  /**
   * Gets the canonical repo path.
   *
   * @param repoPath
   *            the repo path
   * @return the canonical repo path
   */
  public static String getCanonicalRepoPath(String repoPath) {
    if (repoPath == null) {
      return null;
    }
    if (repoPath.endsWith("/...")) {
      return repoPath;
    } else if (repoPath.endsWith("/")) {
      return repoPath + "...";
    } else {
      return repoPath + "/...";
    }
  }

  /**
   * Parse the changelist string to a changelist number. Convert the "default"
   * changelist string to the default changelist number. If it is negative
   * return unknown changelist. Otherwise, return the converted changelist
   * number.
   *
   * @param changelist
   *            the changelist
   * @return the int
   */
  public static int parseChangelist(String changelist) {
    if (!isEmpty(changelist)) {
      if (changelist.trim().equalsIgnoreCase("default")) {
        return IChangelist.DEFAULT;
      }
      try {
        int changelistId = Integer.parseInt(changelist);
        if (changelistId < 0) {
          return IChangelist.UNKNOWN;
        }
        return changelistId;
      } catch (NumberFormatException e) {
        // Suppress error
      }
    }
    return IChangelist.UNKNOWN;
  }

  /**
   * Perforce wildcards expansion.
   *
   * @param filePath the file path
   * @return the string
   */
  public static String encodeWildcards(String filePath) {
    String path = new String();
    if (filePath != null) {
      path = filePath.replaceAll("%", "%25").replaceAll("\\*", "%2A").replaceAll("#", "%23").replaceAll("@", "%40");
    }
    return path;
  }

  /**
   * Checks if is empty.
   *
   * @param value
   *            the value
   * @return true, if is empty
   */
  public static boolean isEmpty(String value) {
    if (value == null || value.trim().length() == 0) {
      return true;
    }
    return false;
  }
}
