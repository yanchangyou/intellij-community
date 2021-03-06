// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.wm.impl.welcomeScreen;

import com.intellij.ide.IdeBundle;
import com.intellij.ide.plugins.CountComponent;
import com.intellij.ide.plugins.InstalledPluginsState;
import com.intellij.ide.plugins.PluginManagerConfigurable;
import com.intellij.ide.plugins.newui.PluginUpdatesService;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.openapi.wm.WelcomeScreenTab;
import com.intellij.openapi.wm.WelcomeTabFactory;
import com.intellij.ui.AncestorListenerAdapter;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBDimension;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.components.BorderLayoutPanel;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.AncestorEvent;
import java.awt.*;

public class PluginsTabFactory implements WelcomeTabFactory {
  private static final Logger LOG = Logger.getInstance(PluginsTabFactory.class);

  @Override
  public @NotNull WelcomeScreenTab createWelcomeTab(@NotNull Disposable parentDisposable) {
    return new TabbedWelcomeScreen.DefaultWelcomeScreenTab(IdeBundle.message("welcome.screen.plugins.title"), WelcomeScreenEventCollector.TabType.TabNavPlugins) {
      private PluginUpdatesService myService;
      private final CountComponent myCountLabel = new CountComponent();
      private JList<? extends WelcomeScreenTab> myList;

      {
        myKeyComponent.setBorder(JBUI.Borders.empty(8, 0, 8, 8));
        myKeyComponent.add(myCountLabel, BorderLayout.EAST);
        myCountLabel.setVisible(false);

        UIUtil.invokeLaterIfNeeded(() -> {
          myService = PluginUpdatesService.connectWithCounter(countValue -> {
            @NlsSafe String text = countValue == null || countValue <= 0 ? null : countValue.toString();
            myCountLabel.setText(text);
            myCountLabel.setVisible(text != null);
            if (myList != null) {
              myList.repaint();
            }
          });
        });
      }

      @Override
      public @NotNull JComponent getKeyComponent(@NotNull JList<? extends WelcomeScreenTab> list) {
        if (myList == null) {
          list.addAncestorListener(new AncestorListenerAdapter() {
            @Override
            public void ancestorRemoved(AncestorEvent event) {
              if (myService != null) {
                myService.dispose();
              }
            }
          });
        }
        myList = list;
        return super.getKeyComponent(list);
      }

      @Override
      protected JComponent buildComponent() {
        PluginManagerConfigurable configurable = new PluginManagerConfigurable();
        BorderLayoutPanel pluginsPanel = UI.Panels.simplePanel(configurable.createComponent()).addToTop(configurable.getTopComponent())
          .withBorder(JBUI.Borders.customLine(JBColor.border(), 0, 1, 0, 0));
        configurable.getTopComponent().setPreferredSize(new JBDimension(configurable.getTopComponent().getPreferredSize().width, 35));
        pluginsPanel.addAncestorListener(new AncestorListenerAdapter() {
          @Override
          public void ancestorRemoved(AncestorEvent event) {
            if (!configurable.isModified()) return;
            try {
              configurable.apply();
              WelcomeScreenEventCollector.logPluginsModified();
              InstalledPluginsState.getInstance().runShutdownCallback();
            }
            catch (ConfigurationException exception) {
              LOG.error(exception);
            }
          }
        });
        return pluginsPanel;
      }
    };
  }
}
