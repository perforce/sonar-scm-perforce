/*
 * SonarQube :: Plugins :: SCM :: Perforce
 * Copyright (C) 2014-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
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
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plugins.scm.perforce;

import java.io.File;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Properties;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.utils.MessageException;

import com.perforce.p4java.client.IClient;
import com.perforce.p4java.client.IClientViewMapping;
import com.perforce.p4java.core.file.FileSpecBuilder;
import com.perforce.p4java.core.file.IFileSpec;
import com.perforce.p4java.exception.MessageSeverityCode;
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.impl.generic.client.ClientView;
import com.perforce.p4java.impl.generic.client.ClientView.ClientViewMapping;
import com.perforce.p4java.impl.mapbased.rpc.RpcPropertyDefs;
import com.perforce.p4java.impl.mapbased.rpc.sys.helper.RpcSystemFileCommandsHelper;
import com.perforce.p4java.option.UsageOptions;
import com.perforce.p4java.option.server.TrustOptions;
import com.perforce.p4java.server.IOptionsServer;
import com.perforce.p4java.server.ServerFactory;
import com.perforce.p4java.server.callback.ICommandCallback;

public class PerforceExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(PerforceExecutor.class);

  /** Perforce server. */
  private IOptionsServer server;

  /** Perforce client. */
  private IClient client;

  private final PerforceConfiguration config;

  /**
   * Instantiates a new p4 command helper.
   *
   * @param config
   *            the plugin configuration
   * @param workDir
   *            the working directory
   */
  public PerforceExecutor(PerforceConfiguration config, File workDir) {
    this.config = config;
    init(workDir);
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
   * Initialize Perforce server and client instances.
   *
   */
  protected void init(File workDir) {
    // Initialize the Perforce server.
    initServer();
    // Initialize the Perforce client.
    initClient(workDir);
  }

  /**
   * Cleanup Perforce server and client instances; logout, disconnect, etc.
   *
   */
  public void clean() {
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
  private void initServer() {

    try {
      createServer();
      // Connect to the server.
      server.connect();
      // Set the Perforce charset.
      String charset = config.charset();
      if (charset != null && server.isConnected() && server.supportsUnicode()) {
        server.setCharsetName(charset);
      }
      // Set server user.
      String username = config.username();
      if (username != null) {
        server.setUserName(username);
        // Check if user is already logged (reuse previous ticket)
        if (!isLogin(server)) {
          // Login to the server with a password.
          // Password can be null if it is not needed (i.e. SSO logins).
          server.login(config.password(), null);
        }
      }
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(e.getLocalizedMessage(), e);
    } catch (P4JavaException e) {
      throw new IllegalStateException(e.getLocalizedMessage(), e);
    }
  }

  private static boolean isLogin(IOptionsServer connection) throws P4JavaException {
    String status = connection.getLoginStatus();
    LOG.debug(status);
    if (status.contains("not necessary")) {
      return true;
    }
    if (status.contains("ticket expires in")) {
      return true;
    }
    // If there is a broker or something else that swallows the message
    return status.isEmpty();
  }

  private void createServer() throws URISyntaxException, P4JavaException {
    // Set default system file helper
    ServerFactory.setRpcFileSystemHelper(new RpcSystemFileCommandsHelper());
    // Get an instance of the P4J server.
    if (StringUtils.isEmpty(config.port())) {
      throw MessageException.of("Please configure perforce port using " + PerforceConfiguration.PORT_PROP_KEY);
    }

    Properties props = new Properties();
    props.put(RpcPropertyDefs.RPC_SOCKET_SO_TIMEOUT_NICK, config.sockSoTimeout());

    UsageOptions usageOptions = new UsageOptions(null);
    // Param is nullable
    usageOptions.setHostName(config.clientImpersonatedHostname());

    if (config.useSsl()) {
      server = ServerFactory.getOptionsServer("p4javassl://" + config.port(), props, usageOptions);
      server.addTrust(new TrustOptions().setAutoAccept(true));
    } else {
      server = ServerFactory.getOptionsServer("p4java://" + config.port(), props, usageOptions);
    }
    // Register server callback.
    server.registerCallback(new CommandLogger());
  }

  private static class CommandLogger implements ICommandCallback {
    @Override
    public void receivedServerMessage(int key, int genericCode, int severityCode, String message) {
      // Log warning messages from server, since it's not included in the other callback methods.
      if (severityCode == MessageSeverityCode.E_WARN) {
        LOG.warn(message);
      }
    }

    @Override
    public void receivedServerInfoLine(int key, String infoLine) {
      LOG.debug(infoLine);
    }

    @Override
    public void receivedServerErrorLine(int key, String errorLine) {
      LOG.error(errorLine);
    }

    @Override
    public void issuingServerCommand(int key, String command) {
      LOG.debug(command);
    }

    @Override
    public void completedServerCommand(int key, long millisecsTaken) {
      LOG.debug("Command completed in " + millisecsTaken + "ms");
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
   * @param basedir
   *            the basedir
   * @param p4ClientName
   *            the Perforce client name
   * @return the client view mapping
   */
  private ClientViewMapping createClientViewMapping(File basedir, String p4ClientName) {
    // Create a new client
    String repoPath = getRepoLocation(encodeWildcards(basedir.getAbsolutePath()));
    String viewPath = getCanonicalRepoPath(repoPath);
    return new ClientViewMapping(0, viewPath, "//" + p4ClientName + "/...");
  }

  /**
   * Gets the repo location.
   *
   * @param path
   *            the path
   * @return the repo location
   */
  @Nullable
  private String getRepoLocation(@Nonnull String path) {
    String location = null;
    if (StringUtils.isNotBlank(path) && client != null) {
      try {
        List<IFileSpec> fileSpecs = client.where(FileSpecBuilder.makeFileSpecList(path));
        for (IFileSpec fileSpec : fileSpecs) {
          if (fileSpec != null && StringUtils.isNotBlank(fileSpec.getDepotPathString())) {
            location = fileSpec.getDepotPathString();
            break;
          }
        }
      } catch (P4JavaException e) {
        throw new IllegalStateException(e);
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
  @Nullable
  private static String getCanonicalRepoPath(@Nullable String repoPath) {
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
   * Perforce wildcards expansion.
   *
   * @param filePath the file path
   * @return the string
   */
  @Nonnull
  static String encodeWildcards(@Nullable String filePath) {
    if (filePath != null) {
      return filePath.replaceAll("%", "%25").replaceAll("\\*", "%2A").replaceAll("#", "%23").replaceAll("@", "%40");
    }
    return "";
  }

}
