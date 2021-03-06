package com.github.langebangen.kensa.audio.lavaplayer;

import java.util.HashMap;
import java.util.Map;

import sx.blah.discord.api.IDiscordClient;
import sx.blah.discord.handle.obj.ActivityType;
import sx.blah.discord.handle.obj.IGuild;
import sx.blah.discord.handle.obj.StatusType;

import com.github.langebangen.kensa.audio.MusicPlayer;
import com.github.langebangen.kensa.listener.event.KensaEvent;
import com.github.langebangen.kensa.util.TrackUtils;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeSearchProvider;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.wrapper.spotify.SpotifyApi;

/**
 * Factory for creating {@link MusicPlayer}s
 *
 * @author langen
 */
@Singleton
public class MusicPlayerManager
{
	public final Map<Long, MusicPlayer> musicPlayers;
	private final AudioPlayerManager playerManager;
	private final YoutubePlaylistSearchProvider ytPlaylistSearchProvider;
	private final YoutubeSearchProvider ytSearchProvider;
	private final IDiscordClient client;
	private final SpotifyApi spotifyApi;

	@Inject
	private MusicPlayerManager(IDiscordClient client, SpotifyApi spotifyApi,
		AudioPlayerManager playerManager)
	{
		this.client = client;
		this.spotifyApi = spotifyApi;
		this.musicPlayers = new HashMap<>();
		this.playerManager = playerManager;
		YoutubeAudioSourceManager ytSourceManager = new YoutubeAudioSourceManager(true);
		ytSearchProvider = new YoutubeSearchProvider(ytSourceManager);
		ytPlaylistSearchProvider = new YoutubePlaylistSearchProvider(ytSourceManager);
	}

	/**
	 * Gets the {@link MusicPlayer} associated with the specified {@link KensaEvent}.
	 * If no such {@link MusicPlayer} exists then it is created and the returned.
	 *
	 * @param event
	 * 		the {@link KensaEvent}
	 *
	 * @return
	 * 		the {@link MusicPlayer}
	 */
	public MusicPlayer getMusicPlayer(KensaEvent event)
	{
		return getMusicPlayer(event.getTextChannel().getGuild());
	}

	/**
	 * Gets the {@link MusicPlayer} associated with the specified {@link IGuild}.
	 * If no such {@link MusicPlayer} exists then it is created and the returned.
	 *
	 * @param guild
	 * 		the {@link IGuild}
	 *
	 * @return
	 * 		the {@link MusicPlayer}
	 */
	public MusicPlayer getMusicPlayer(IGuild guild)
	{
		long guildId = Long.parseLong(guild.getStringID());

		MusicPlayer musicPlayer = musicPlayers.get(guildId);
		if(musicPlayer == null)
		{
			AudioPlayer audioPlayer = playerManager.createPlayer();
			audioPlayer.setVolume(50);
			TrackScheduler scheduler = new ClientTrackScheduler(audioPlayer);
			audioPlayer.addListener(scheduler);
			musicPlayer = new LavaMusicPlayer(scheduler, playerManager, ytSearchProvider,
				ytPlaylistSearchProvider, spotifyApi);
			guild.getAudioManager().setAudioProvider(new AudioProvider(audioPlayer));
			musicPlayers.put(guildId, musicPlayer);
		}

		return musicPlayer;
	};

	/**
	 * A {@link TrackScheduler} which updates the "Now playing"
	 * text for the Kensa bot.
	 *
	 * Note that this class is not really suited for if Kensa
	 * is connected to multiple guilds, since the "Now playing"
	 * text is global.
	 *
	 * Currently my use case is only for one Guild so I'm going
	 * to use this for now since its a pretty sweet little function.
	 */
	private class ClientTrackScheduler
		extends TrackScheduler
	{
		/**
		 * @param player
		 * 	The audio player this scheduler uses
		 */
		public ClientTrackScheduler(AudioPlayer player)
		{
			super(player);
		}

		@Override
		public void onTrackStart(AudioPlayer player, AudioTrack track)
		{
			super.onTrackStart(player, track);
			client.changePresence(StatusType.ONLINE, ActivityType.PLAYING, TrackUtils.getReadableTrack(track));
		}

		@Override
		public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason)
		{
			if(!hasNextTrack())
			{
				client.changePresence(StatusType.ONLINE);
			}
			super.onTrackEnd(player, track, endReason);
		}
	}
}
