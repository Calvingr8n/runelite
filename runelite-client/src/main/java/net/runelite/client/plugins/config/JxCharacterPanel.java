/*
 * Copyright (c) 2023 Adam <Adam@sigterm.info>
 * Copyright (c) 2023 Abex
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.config;

import com.google.common.base.CharMatcher;
import com.google.common.base.Strings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.account.SessionManager;
import net.runelite.client.config.*;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.events.SessionClose;
import net.runelite.client.events.SessionOpen;
import net.runelite.client.plugins.screenmarkers.ScreenMarkerPlugin;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.DragAndDropReorderPane;
import net.runelite.client.ui.components.MouseDragEventForwarder;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.SwingUtil;
import net.runelite.client.util.Text;
import net.runelite.http.api.config.Profile;

import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
class JxCharacterPanel extends PluginPanel
{

	private static final ImageIcon LINK_ICON;
	private static final ImageIcon LINK_ACTIVE_ICON;
	private static final ImageIcon ARROW_RIGHT_ICON = new ImageIcon(ImageUtil.loadImageResource(JxCharacterPanel.class, "/util/arrow_right.png"));

	private final ConfigManager configManager;
	private final ProfileManager profileManager;
	private final SessionManager sessionManager;
	private final ScheduledExecutorService executor;

	private final DragAndDropReorderPane characterList;

	private Map<String, CharacterCard> cards = new HashMap<>();

	private boolean active;

	static
	{
		BufferedImage link = ImageUtil.loadImageResource(JxCharacterPanel.class, "/util/link.png");
		LINK_ICON = new ImageIcon(link);
		LINK_ACTIVE_ICON = new ImageIcon(ImageUtil.recolorImage(link, ColorScheme.BRAND_ORANGE));

		BufferedImage sync = ImageUtil.loadImageResource(JxCharacterPanel.class, "cloud_sync.png");
	}

	@Inject
    JxCharacterPanel(
		ConfigManager configManager,
		ProfileManager profileManager,
		SessionManager sessionManager,
		ScheduledExecutorService executor
	)
	{
		this.profileManager = profileManager;
		this.configManager = configManager;
		this.sessionManager = sessionManager;
		this.executor = executor;

		setBorder(new EmptyBorder(10, 10, 10, 10));

		GroupLayout layout = new GroupLayout(this);
		setLayout(layout);

		characterList = new DragAndDropReorderPane();


		JLabel info = new JLabel("<html>"
			+ "Characters of your Jagex Account that you can switch between by double clicking and re-logging.");

		layout.setVerticalGroup(layout.createSequentialGroup()
			.addComponent(characterList)
			.addGap(8)
			.addGroup(layout.createParallelGroup())
			.addGap(8)
			.addComponent(info));

		layout.setHorizontalGroup(layout.createParallelGroup()
			.addComponent(characterList)
			.addGroup(layout.createSequentialGroup()
				.addGap(8))
			.addComponent(info));

		{
			Object refresh = "this could just be a lambda, but no, it has to be abstracted";
			getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), refresh);
			getActionMap().put(refresh, new AbstractAction()
			{
				@Override
				public void actionPerformed(ActionEvent e)
				{
					reload();
				}
			});
		}
	}

	@Override
	public void onActivate()
	{
		active = true;
		reload();
	}

	@Override
	public void onDeactivate()
	{
		active = false;
		SwingUtil.fastRemoveAll(characterList);
		cards.clear();
	}

	@Subscribe
	private void onProfileChanged(ProfileChanged ev)
	{
		if (!active)
		{
			return;
		}

		SwingUtilities.invokeLater(() ->
		{
			for (CharacterCard card : cards.values())
			{
				card.setActive(false);
			}
			// TODO: Determine what this does
			CharacterCard card = cards.get(configManager.getProfile().getId());
			if (card == null)
			{
				reload();
				return;
			}

			card.setActive(true);
		});
	}

	@Subscribe
	private void onRuneScapeProfileChanged(RuneScapeProfileChanged ev)
	{
		if (!active)
		{
			return;
		}

		reload();
	}

	@Subscribe
	public void onSessionOpen(SessionOpen sessionOpen)
	{
		if (!active)
		{
			return;
		}

		reload();
	}

	@Subscribe
	public void onSessionClose(SessionClose sessionClose)
	{
		if (!active)
		{
			return;
		}

		reload();
	}

	private void reload()
	{
		executor.submit(() ->
		{
			try
			{
				// TODO remove this section
				String sessionId = System.getenv("JX_SESSION_ID");
				if (sessionId != null && !sessionId.isEmpty()) {
					log.info("Found Session ID");
					getJxAccounts(sessionId);
					//log.info(sessionId);
				}
				else
				{
					log.info("Didn't find session id");
				}
			} catch (Exception ex)
			{
				log.warn("Failed to reload", ex);
			}
		});
	}

	/*
	private void reload(List<ConfigProfile> profiles)
	{
		SwingUtilities.invokeLater(() ->
		{
			SwingUtil.fastRemoveAll(profilesList);

			Map<Long, ProfileCard> prevCards = cards;
			cards = new HashMap<>();

			long activePanel = configManager.getProfile().getId();
			final String rsProfileKey = configManager.getRSProfileKey();
			boolean limited = profiles.stream().filter(v -> !v.isInternal()).count() >= MAX_PROFILES;

			for (ConfigProfile profile : profiles)
			{
				if (profile.isInternal())
				{
					continue;
				}

				ProfileCard prev = prevCards.get(profile.getId());
				final long id = profile.getId();
				final List<String> defaultForRsProfiles = profile.getDefaultForRsProfiles();
				ProfileCard pc = new ProfileCard(
					profile,
					activePanel == id,
					defaultForRsProfiles != null && defaultForRsProfiles.contains(rsProfileKey),
					limited,
					prev);
				cards.put(profile.getId(), pc);
				profilesList.add(pc);
			}

			addButton.setEnabled(!limited);
			importButton.setEnabled(!limited);

			profilesList.revalidate();
		});
	}
	*/

	private void getJxAccounts(String sessionId)
	{
		SwingUtilities.invokeLater(() ->
		{
			String characterName = System.getenv("JX_CHARACTER_ID");
			SwingUtil.fastRemoveAll(characterList);
			try {
				okhttp3.OkHttpClient httpClient = new okhttp3.OkHttpClient();
				okhttp3.Request request = new okhttp3.Request.Builder()
						.url("https://auth.runescape.com/game-session/v1/accounts?fetchMembership=true")
						.addHeader("Authorization", "Bearer " + sessionId)
						.build();
				log.info("Built request");
				try (okhttp3.Response response = httpClient.newCall(request).execute()) {
					log.info("Sent request");
					if (response.isSuccessful() && response.body() != null) {
						JsonParser parser = new JsonParser();
						String body = response.body().string();
						JsonArray arr = parser.parse(body).getAsJsonArray();
						log.info("Successful response");
						for (JsonElement el : arr) {
							com.google.gson.JsonObject obj = el.getAsJsonObject();
							String accountId = obj.get("accountId").getAsString();
							String displayName = obj.get("displayName").getAsString();
							//Map<String, ProfileCard> prevCards = cards;
							cards = new HashMap<>();
							JxCharacter jx = new JxCharacter();
							jx.id = accountId;
							jx.name = displayName;
							jx.active = characterName.equals(displayName);
							CharacterCard pc = new CharacterCard(
									jx);
							cards.put(displayName, pc);
							characterList.add(pc);
						}

					}
					else
					{
						log.info("Failed request");
					}
				}
				catch (Exception ex) {
					log.warn("Failed to load execute web request", ex);
				}
			}
			catch (Exception ex) {
				log.warn("Failed to load jagex accounts", ex);
			}
		});
	}

	private static class JxCharacter
	{
		@Getter
		@Setter(AccessLevel.PACKAGE)
		private String id;
		@Getter
		@Setter(AccessLevel.PACKAGE)
		private String name;
		@Getter
		@Setter
		private boolean active;

	}

	private class CharacterCard extends JPanel
	{
		private static final int CARD_WIDTH = 227;
		private static final int LEFT_BORDER_WIDTH = 4;
		private static final int LEFT_GAP = 4;

		private final JxCharacter jxCharacter;
		private final JTextField name;
		private final JButton activate;
		private final JPanel buttons;
		private boolean active;

		private CharacterCard(JxCharacter jxCharacter)
		{
			this.jxCharacter = jxCharacter;

			setBackground(ColorScheme.DARKER_GRAY_COLOR);

			name = new JTextField();
			name.setText(jxCharacter.getName());
			name.setEditable(false);
			name.setEnabled(false);
			name.setOpaque(false);
			name.setBorder(null);

			activate = new JButton(ARROW_RIGHT_ICON);
			activate.setDisabledIcon(ARROW_RIGHT_ICON);
			// TODO: Make this switch character
			activate.addActionListener(ev -> switchToCharacter(jxCharacter));
			SwingUtil.removeButtonDecorations(activate);

			{
				buttons = new JPanel();
				buttons.setOpaque(false);
				buttons.setLayout(new GridLayout(1, 0, 0, 0));

				// Ensure buttons do not expand beyond the intended card width; this would cause the activate button to
				// disappear off the right edge of the card.
				final int maxButtonsWidth = CARD_WIDTH - LEFT_BORDER_WIDTH - LEFT_GAP - activate.getPreferredSize().width;
				if (buttons.getPreferredSize().width > maxButtonsWidth)
				{
					buttons.setMinimumSize(new Dimension(maxButtonsWidth, buttons.getMinimumSize().height));
					buttons.setPreferredSize(new Dimension(maxButtonsWidth, buttons.getPreferredSize().height));
				}
			}

			{
				GroupLayout layout = new GroupLayout(this);
				this.setLayout(layout);

				layout.setVerticalGroup(layout.createParallelGroup()
					.addGroup(layout.createSequentialGroup()
						.addComponent(name, 24, 24, 24)
						.addComponent(buttons))
					.addComponent(activate, GroupLayout.DEFAULT_SIZE, GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE));

				layout.setHorizontalGroup(layout.createSequentialGroup()
					.addGap(LEFT_GAP)
					.addGroup(layout.createParallelGroup()
						.addComponent(name)
						.addComponent(buttons))
					.addComponent(activate));
			}

			MouseAdapter expandListener = new MouseDragEventForwarder(characterList)
			{
				@Override
				public void mouseClicked(MouseEvent ev)
				{
					if (disabled(ev))
					{
						if (ev.getClickCount() == 2)
						{
							if (!active)
							{
								// TODO: switchToCharacter
								switchToCharacter(jxCharacter);
								//switchToProfile(profile.getId());
								log.info("Attempted to switch characters");
							}
						}
						else
						{
							log.info("Would have expanded");
						}
					}
				}

				@Override
				public void mouseEntered(MouseEvent ev)
				{
					if (disabled(ev))
					{
						setBackground(ColorScheme.DARK_GRAY_COLOR);
					}
				}

				@Override
				public void mouseExited(MouseEvent ev)
				{
					if (disabled(ev))
					{
						setBackground(ColorScheme.DARKER_GRAY_COLOR);
					}
				}

				private boolean disabled(MouseEvent ev)
				{
					Component target = ev.getComponent();
					if (target instanceof JButton)
					{
						return !target.isEnabled();
					}
					if (target instanceof JTextField)
					{
						return !((JTextField) target).isEditable();
					}
					return true;
				}
			};
			addMouseListener(expandListener);
			addMouseMotionListener(expandListener);
			name.addMouseListener(expandListener);
			name.addMouseMotionListener(expandListener);
			activate.addMouseListener(expandListener);
			activate.addMouseMotionListener(expandListener);

			setActive(jxCharacter.active);
		}

		void setActive(boolean active)
		{
			this.active = active;
			setBorder(new MatteBorder(0, LEFT_BORDER_WIDTH, 0, 0, active
				? ColorScheme.BRAND_ORANGE
				: ColorScheme.DARKER_GRAY_COLOR));
			activate.setEnabled(!active);
		}

	}


	// TODO: Build switch to character
	private void switchToProfile(long id)
	{
		ConfigProfile profile;
		try (ProfileManager.Lock lock = profileManager.lock())
		{
			profile = lock.findProfile(id);
			if (profile == null)
			{
				log.warn("change to nonexistent profile {}", id);
				// maybe profile was removed by another client, reload the panel
				//reload(lock.getProfiles());
				return;
			}

			log.debug("Switching profile to {}", profile.getName());

			// change active profile
			lock.getProfiles().forEach(p -> p.setActive(false));
			profile.setActive(true);
			lock.dirty();
		}

		executor.submit(() -> configManager.switchProfile(profile));
	}

	private void switchToCharacter(JxCharacter jxCharacter){
		log.info(jxCharacter.name, " ", jxCharacter.id);
		setEnv("JX_CHARACTER_ID", jxCharacter.id);
		setEnv("JX_DISPLAY_NAME", jxCharacter.name);
	}

	static void setEnv(String key, String value)
	{
		try
		{
			log.info("Attempted to set env");
			Class<?> pe = Class.forName("java.lang.ProcessEnvironment");
			java.lang.reflect.Field envField = pe.getDeclaredField("theEnvironment");
			envField.setAccessible(true);
			@SuppressWarnings("unchecked")
			java.util.Map<String, String> env = (java.util.Map<String, String>) envField.get(null);
			env.replace(key,value);
			env.put(key, value);

			log.info("Attempted to set cenv");
			java.lang.reflect.Field ciEnvField = pe.getDeclaredField("theCaseInsensitiveEnvironment");
			ciEnvField.setAccessible(true);
			@SuppressWarnings("unchecked")
			java.util.Map<String, String> cienv = (java.util.Map<String, String>) ciEnvField.get(null);
			cienv.replace(key, value);
			cienv.put(key, value);
		}
		catch (Exception ex)
		{
			log.warn("Some setEnv problem:", ex);
			//System.setProperty(key, value);
		}
	}

	/*
	private void unsetRsProfileDefaultProfile()
	{
		setRsProfileDefaultProfile(-1);
	}


	private void setRsProfileDefaultProfile(long id)
	{
		executor.submit(() ->
		{
			boolean switchProfile = false;
			try (ProfileManager.Lock lock = profileManager.lock())
			{
				final String rsProfileKey = configManager.getRSProfileKey();
				if (rsProfileKey == null)
				{
					return;
				}

				for (final ConfigProfile profile : lock.getProfiles())
				{
					final List<String> defaultForRsProfiles = profile.getDefaultForRsProfiles();
					if (defaultForRsProfiles == null)
					{
						continue;
					}
					if (profile.getDefaultForRsProfiles().remove(rsProfileKey))
					{
						lock.dirty();
					}
				}

				if (id == -1)
				{
					log.debug("Unsetting default profile for rsProfile {}", rsProfileKey);
				}
				else
				{
					final ConfigProfile profile = lock.findProfile(id);
					if (profile == null)
					{
						log.warn("setting nonexistent profile {} as default for rsprofile", id);
						// maybe profile was removed by another client, reload the panel
						reload(lock.getProfiles());
						return;
					}

					log.debug("Setting profile {} as default for rsProfile {}", profile.getName(), rsProfileKey);

					if (profile.getDefaultForRsProfiles() == null)
					{
						profile.setDefaultForRsProfiles(new ArrayList<>());
					}
					profile.getDefaultForRsProfiles().add(rsProfileKey);
					switchProfile = !profile.isActive();
					lock.dirty();
				}

				reload(lock.getProfiles());
			}

			if (switchProfile)
			{
				switchToProfile(id);
			}
		});
	}

	*/

}
