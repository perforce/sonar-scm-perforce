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

import com.google.common.collect.ImmutableList;
import org.sonar.api.BatchComponent;
import org.sonar.api.CoreProperties;
import org.sonar.api.PropertyType;
import org.sonar.api.batch.InstantiationStrategy;
import org.sonar.api.config.PropertyDefinition;
import org.sonar.api.config.Settings;
import org.sonar.api.resources.Qualifiers;

import javax.annotation.CheckForNull;

import java.util.List;

@InstantiationStrategy(InstantiationStrategy.PER_BATCH)
public class PerforceConfiguration implements BatchComponent {

  private static final String FALSE = "false";
  private static final String CATEGORY_PERFORCE = "Perforce";
  public static final String PORT_PROP_KEY = "sonar.perforce.port";
  public static final String USESSL_PROP_KEY = "sonar.perforce.useSsl";
  public static final String USER_PROP_KEY = "sonar.perforce.username";
  public static final String PASSWORD_PROP_KEY = "sonar.perforce.password.secured";
  public static final String CLIENT_PROP_KEY = "sonar.perforce.clientName";

  public static final String CHARSET_PROP_KEY = "sonar.perforce.charset";

  private final Settings settings;

  public PerforceConfiguration(Settings settings) {
    this.settings = settings;
  }

  public static List<PropertyDefinition> getProperties() {
    return ImmutableList.of(
      PropertyDefinition.builder(PORT_PROP_KEY)
        .name("Perforce service port")
        .description("The host and port number of the Perforce service with which to communicate. Format is host:port.")
        .type(PropertyType.STRING)
        .onQualifiers(Qualifiers.PROJECT)
        .category(CoreProperties.CATEGORY_SCM)
        .subCategory(CATEGORY_PERFORCE)
        .index(0)
        .build(),
      PropertyDefinition.builder(USER_PROP_KEY)
        .name("Username")
        .description("Username to be used for Perforce authentication")
        .type(PropertyType.STRING)
        .onQualifiers(Qualifiers.PROJECT)
        .category(CoreProperties.CATEGORY_SCM)
        .subCategory(CATEGORY_PERFORCE)
        .index(1)
        .build(),
      PropertyDefinition.builder(PASSWORD_PROP_KEY)
        .name("Password")
        .description("Password to be used for Perforce authentication")
        .type(PropertyType.PASSWORD)
        .onQualifiers(Qualifiers.PROJECT)
        .category(CoreProperties.CATEGORY_SCM)
        .subCategory(CATEGORY_PERFORCE)
        .index(2)
        .build(),
      PropertyDefinition.builder(CLIENT_PROP_KEY)
        .name("Client workspace name")
        .description("Name of the workspace the project belong to")
        .type(PropertyType.STRING)
        .onlyOnQualifiers(Qualifiers.PROJECT)
        .category(CoreProperties.CATEGORY_SCM)
        .subCategory(CATEGORY_PERFORCE)
        .index(3)
        .build(),
      PropertyDefinition.builder(USESSL_PROP_KEY)
        .name("Use SSL")
        .description("Use SSL protocol (p4javassl://) to connect to server")
        .type(PropertyType.BOOLEAN)
        .defaultValue(FALSE)
        .onQualifiers(Qualifiers.PROJECT)
        .category(CoreProperties.CATEGORY_SCM)
        .subCategory(CATEGORY_PERFORCE)
        .index(4)
        .build(),
      PropertyDefinition.builder(CHARSET_PROP_KEY)
        .name("Perforce charset")
        .description("Character set used for translation of unicode files (P4CHARSET)")
        .type(PropertyType.STRING)
        .category(CoreProperties.CATEGORY_SCM)
        .subCategory(CATEGORY_PERFORCE)
        .index(5)
        .build());
  }

  @CheckForNull
  public String port() {
    return settings.getString(PORT_PROP_KEY);
  }

  @CheckForNull
  public String username() {
    return settings.getString(USER_PROP_KEY);
  }

  @CheckForNull
  public String password() {
    return settings.getString(PASSWORD_PROP_KEY);
  }

  @CheckForNull
  public String charset() {
    return settings.getString(CHARSET_PROP_KEY);
  }

  public boolean useSsl() {
    return settings.getBoolean(USESSL_PROP_KEY);
  }

  @CheckForNull
  public String clientName() {
    return settings.getString(CLIENT_PROP_KEY);
  }

}
