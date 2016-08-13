package com.github.langebangen.kensa.audio;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sx.blah.discord.handle.obj.IChannel;
import sx.blah.discord.util.DiscordException;
import sx.blah.discord.util.MessageBuilder;
import sx.blah.discord.util.MissingPermissionsException;
import sx.blah.discord.util.RateLimitException;
import sx.blah.discord.util.audio.AudioPlayer;
import sx.blah.discord.util.audio.providers.URLProvider;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

/**
 * Static utility class for streaming content from a specified url to the {@link AudioPlayer}
 *
 * @author langen
 */
public class AudioStreamer
{
	private static final Logger logger = LoggerFactory.getLogger(AudioStreamer.class);

	/**
	 * Streams the content located on the specified URL to the specified player.
	 * Will send a message that the content has been added to the playlist queue.
	 *
	 * @param urlString
	 *      the url string
	 * @param channel
	 *      the {@link IChannel}
	 */
	public static void stream(String urlString, IChannel channel)
	{
		AudioPlayer player = AudioPlayer.getAudioPlayerForGuild(channel.getGuild());

		if(urlString.contains("youtube.com") || urlString.contains("youtu.be"))
		{
			try
			{
				streamYoutube(urlString, player, channel);
			}
			catch(UnsupportedAudioFileException | DiscordException | IOException
					| RateLimitException | MissingPermissionsException e)
			{
				logger.error("Error when streaming content from youtube.", e);
			}
		}
		else
		{
			try
			{
				URL url = new URL(urlString);
				ExtendedTrack track = new ExtendedTrack(new URLProvider(url),
						TrackSource.URL, url.toString(), null, null);
				player.queue(track);
				sendPlayMessage(track, channel);
			}
			catch(UnsupportedAudioFileException | IOException e)
			{
				logger.error("Error when streaming content from url", e);
			}
		}
	}

	/**
	 * Streams the content from an youtube url link.
	 *
	 * @param url
	 *      the url
	 * @param player
	 *      the {@link AudioPlayer}
	 * @param channel
	 *      the {@link IChannel}
	 *
	 * @throws MissingPermissionsException
	 * @throws IOException
	 * @throws RateLimitException
	 * @throws DiscordException
	 * @throws UnsupportedAudioFileException
	 */
	private static void streamYoutube(String url, AudioPlayer player, IChannel channel)
			throws MissingPermissionsException, IOException, RateLimitException, DiscordException,
			UnsupportedAudioFileException
	{
		//Credits to pangeacake: https://gist.github.com/pangeacake/1fbad48728d56f563cbbdba23243423b
		final String[] title = new String[1];
		final String[] readableDuration = new String[1];
		new Thread(() ->
		{
			ProcessBuilder info = new ProcessBuilder(
					"youtube-dl",
					"-q",                   //quiet. No standard out.
					"-j",                   //Print JSON
					"--flat-playlist",      //Get ONLY the urls of the playlist if this is a playlist.
					"--ignore-errors",
					"--skip-download",
					"--", url
			);

			byte[] infoData = new byte[0];
			try
			{
				Process infoProcess = info.start();
				infoData = IOUtils.toByteArray(infoProcess.getInputStream());
			}
			catch(IOException e)
			{
				e.printStackTrace();
			}
			if(infoData == null || infoData.length == 0)
			{
				throw new NullPointerException("The youtube-dl info process returned no data!");
			}

			String sInfo = new String(infoData);
			Scanner scanner = new Scanner(sInfo);

			JsonParser parser = new JsonParser();
			JsonObject json = parser.parse(scanner.nextLine()).getAsJsonObject();

			title[0] = json.has("title")
					? json.get("title").getAsString() : (json.has("fulltitle")
					? json.get("fulltitle").getAsString() : null);

			int durationInSeconds = json.has("duration") ? json.get("duration").getAsInt() : -1;

			readableDuration[0] = null;
			if(durationInSeconds != -1)
			{
				readableDuration[0] = String.format(" [%d min, %d sec]",
						TimeUnit.SECONDS.toMinutes(durationInSeconds),
						TimeUnit.SECONDS.toSeconds(durationInSeconds) -
								TimeUnit.MINUTES.toSeconds(TimeUnit.SECONDS.toMinutes(durationInSeconds))
				);
			}
		}).start();

		ProcessBuilder youtube = new ProcessBuilder("youtube-dl",
				"-q",
				"-f", "bestaudio",
				"--exec", "ffmpeg -hide_banner -nostats -loglevel panic -y -i {} -vn -q:a 6 -f mp3 pipe:1",
				"-o", "%(id)s", "--", url);

		Process yProcess = youtube.start();

		new Thread("youtube-dl ErrorStream")
		{
			@Override
			public void run()
			{
				try
				{
					InputStream fromYTDL = null;

					fromYTDL = yProcess.getErrorStream();
					if(fromYTDL == null)
					{
						logger.error("youtube-dl ErrorStream is null");
					}

					byte[] buffer = new byte[1024];
					int amountRead = -1;
					while(!isInterrupted() && ((amountRead = fromYTDL.read(buffer)) > -1))
					{
						logger.warn("youtube-dl error: " + new String(Arrays.copyOf(buffer, amountRead)));
					}
				}
				catch(IOException e)
				{
					logger.debug("youtube-dl", e);
				}
			}
		}.start();

		ExtendedTrack track = new ExtendedTrack(
				AudioSystem.getAudioInputStream(yProcess.getInputStream()),
				TrackSource.YOUTUBE, url, title[0], readableDuration[0]);

		player.queue(track);

		sendPlayMessage(track, channel);
	}

	/**
	 * Sends a play message with the specified message builder.
	 *
	 * @param track
	 *      the {@link AudioPlayer.Track} that was queued
	 * @param channel
	 *      the {@link IChannel}
	 */
	private static void sendPlayMessage(AudioPlayer.Track track, IChannel channel)
	{
		try
		{
			new MessageBuilder(channel.getClient())
					.withChannel(channel)
					.appendContent("Queued ")
					.appendContent(track.toString(), MessageBuilder.Styles.BOLD)
					.build();
		}
		catch(DiscordException | RateLimitException | MissingPermissionsException e)
		{
			e.printStackTrace();
		}
	}
}