/*
    Copyright 2017 Will Winder

    This file is part of Universal Gcode Sender (UGS).

    UGS is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    UGS is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with UGS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.makesafe.ugs;

import com.willwinder.ugs.nbp.lib.lookup.CentralLookup;
import com.willwinder.ugs.nbp.lib.services.LocalizingService;
import static com.willwinder.ugs.nbp.lib.services.LocalizingService.lang;
import com.willwinder.ugs.nbp.lib.services.TopComponentLocalizer;
import com.willwinder.universalgcodesender.i18n.Localization;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.services.LookupService;

import org.netbeans.api.settings.ConvertAsProperties;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.windows.TopComponent;

import javax.swing.*;
import java.awt.BorderLayout;
import org.openide.modules.OnStart;

/**
 * Top component which displays something.
 */
@ConvertAsProperties(
        dtd = "-//com.makesafe.ugs//MakeSafePanel//EN",
        autostore = false
)
@TopComponent.Description(
        preferredID = "MakeSafePanelTopComponent",
        //iconBase="SET/PATH/TO/ICON/HERE",
        persistenceType = TopComponent.PERSISTENCE_ALWAYS
)
@TopComponent.Registration(mode = "output", openAtStartup = false)
@ActionID(
        category = MakeSafePanelTopComponent.MakeSafePanelCategory,
        id = MakeSafePanelTopComponent.MakeSafePanelActionId)
@ActionReference(path = LocalizingService.MENU_WINDOW_PLUGIN)
@TopComponent.OpenActionRegistration(
        displayName = "MakeSafePanel",
        preferredID = "MakeSafePanelTopComponent"
)
public final class MakeSafePanelTopComponent extends TopComponent {
  public final static String MakeSafePanelTitle = "MakeSafePanel"; //Localization.getString("platform.window.template-module", lang);
  public final static String MakeSafePanelTooltip = "MakeSafePanel tooltip"; //Localization.getString("platform.window.template-module.tooltip", lang);
  public final static String MakeSafePanelActionId = "com.makesafe.ugs.MakeSafePanelTopComponent";
  public final static String MakeSafePanelCategory = LocalizingService.CATEGORY_WINDOW;

  @OnStart
  public static class Localizer extends TopComponentLocalizer {
    public Localizer() {
      super(MakeSafePanelCategory, MakeSafePanelActionId, MakeSafePanelTitle);
    }
  }

  private final BackendAPI backend;

  public MakeSafePanelTopComponent() {
    setName(MakeSafePanelTitle);
    setToolTipText(MakeSafePanelTooltip);

    backend = LookupService.lookup(BackendAPI.class);

    setLayout(new BorderLayout());
    add(new JLabel("Hello MakeSafePanel!"), BorderLayout.CENTER);
  }

  @Override
  public void componentOpened() {
  }

  @Override
  public void componentClosed() {
  }

  public void writeProperties(java.util.Properties p) {
    // better to version settings since initial version as advocated at
    // http://wiki.apidesign.org/wiki/PropertyFiles
    p.setProperty("version", "1.0");
  }

  public void readProperties(java.util.Properties p) {
    String version = p.getProperty("version");
  }
}
