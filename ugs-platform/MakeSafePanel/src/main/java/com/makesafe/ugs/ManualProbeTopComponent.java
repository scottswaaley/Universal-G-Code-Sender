/*
    Copyright 2026 MAKESafe Tools

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

import com.makesafe.ugs.manualprobe.ManualProbePanel;
import com.makesafe.ugs.manualprobe.ManualProbeService;
import com.willwinder.ugs.nbp.lib.services.LocalizingService;
import com.willwinder.ugs.nbp.lib.services.TopComponentLocalizer;
import com.willwinder.universalgcodesender.listeners.UGSEventListener;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.UGSEvent;
import com.willwinder.universalgcodesender.model.events.ControllerStateEvent;
import com.willwinder.universalgcodesender.model.events.ControllerStatusEvent;
import com.willwinder.universalgcodesender.services.LookupService;

import org.netbeans.api.settings.ConvertAsProperties;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.modules.OnStart;
import org.openide.windows.TopComponent;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;

/**
 * Window hosting the MAKESafe manual probe: a direction pad that finds an edge
 * and turns the points it touched into a work zero.
 *
 * <p>Sibling of {@link MakeSafePanelTopComponent}. That one runs the fixed
 * BitZero routines; this one is the freehand version for any edge or surface.
 */
@ConvertAsProperties(
        dtd = "-//com.makesafe.ugs//ManualProbe//EN",
        autostore = false
)
@TopComponent.Description(
        preferredID = "ManualProbeTopComponent",
        persistenceType = TopComponent.PERSISTENCE_ALWAYS
)
@TopComponent.Registration(mode = "output", openAtStartup = false)
@ActionID(
        category = ManualProbeTopComponent.ManualProbeCategory,
        id = ManualProbeTopComponent.ManualProbeActionId)
@ActionReference(path = LocalizingService.MENU_WINDOW_PLUGIN)
@TopComponent.OpenActionRegistration(
        displayName = "MAKESafe Manual Probe",
        preferredID = "ManualProbeTopComponent"
)
public final class ManualProbeTopComponent extends TopComponent implements UGSEventListener {
  public final static String ManualProbeTitle = "MAKESafe Manual Probe";
  public final static String ManualProbeTooltip = "Find an edge by probing in a direction";
  public final static String ManualProbeActionId = "com.makesafe.ugs.ManualProbeTopComponent";
  public final static String ManualProbeCategory = LocalizingService.CATEGORY_WINDOW;

  @OnStart
  public static class Localizer extends TopComponentLocalizer {
    public Localizer() {
      super(ManualProbeCategory, ManualProbeActionId, ManualProbeTitle);
    }
  }

  private final BackendAPI backend;
  private final transient ManualProbeService probeService;
  private final ManualProbePanel probePanel;

  public ManualProbeTopComponent() {
    setName(ManualProbeTitle);
    setToolTipText(ManualProbeTooltip);

    backend = LookupService.lookup(BackendAPI.class);
    probeService = new ManualProbeService(backend);
    probePanel = new ManualProbePanel(probeService, backend);

    // WEST, not CENTER: the panel keeps its own preferred width and the rest of
    // the dock is left empty. Docked straight into CENTER it would be stretched
    // to the full width of whatever mode it lands in, and the "output" dock is
    // as wide as the window - which turns every button into a letterbox.
    JPanel holder = new JPanel(new BorderLayout());
    holder.add(probePanel, BorderLayout.WEST);

    setLayout(new BorderLayout());
    add(new JScrollPane(holder), BorderLayout.CENTER);
  }

  @Override
  public void componentOpened() {
    backend.addUGSEventListener(this);
    probeService.attach();
    probePanel.refresh();
  }

  @Override
  public void componentClosed() {
    backend.removeUGSEventListener(this);
    // Abandon any run too: a closed window would otherwise keep issuing probes
    // and rapids with no visible status and no reachable Stop button.
    probeService.detach();
  }

  /**
   * Controller status arrives at the poll rate, several times a second, and only
   * the contact indicator needs it that often. Button and status state is driven
   * from actual state transitions instead, which is both sufficient and what
   * keeps the panel's own status messages from being overwritten between polls.
   */
  @Override
  public void UGSEvent(UGSEvent event) {
    if (event instanceof ControllerStatusEvent) {
      probePanel.setProbeContact(
          ((ControllerStatusEvent) event).getStatus().getEnabledPins().probe());
    } else if (event instanceof ControllerStateEvent) {
      probePanel.refresh();
    }
  }

  public void writeProperties(java.util.Properties p) {
    p.setProperty("version", "1.0");
  }

  public void readProperties(java.util.Properties p) {
    String version = p.getProperty("version");
  }
}
