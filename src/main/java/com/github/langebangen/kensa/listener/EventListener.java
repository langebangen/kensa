package com.github.langebangen.kensa.listener;

import com.github.langebangen.kensa.command.Action;
import com.github.langebangen.kensa.command.Command;
import com.github.langebangen.kensa.listener.event.*;
import com.google.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.UrlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rita.RiMarkov;
import sx.blah.discord.api.IDiscordClient;
import sx.blah.discord.api.events.EventDispatcher;
import sx.blah.discord.api.events.EventSubscriber;
import sx.blah.discord.handle.impl.events.ReadyEvent;
import sx.blah.discord.handle.impl.events.guild.channel.message.MessageReceivedEvent;
import sx.blah.discord.handle.obj.IChannel;
import sx.blah.discord.handle.obj.IMessage;
import sx.blah.discord.handle.obj.IUser;
import sx.blah.discord.handle.obj.IVoiceChannel;
import sx.blah.discord.util.audio.AudioPlayer;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import javax.inject.Named;

/**
 * EventListener which listens on events from discord.
 *
 * @author langen
 */
public class EventListener
	extends AbstractEventListener
{
	private static final Logger logger = LoggerFactory.getLogger(EventListener.class);

	private static final String PUNCTUATIONS = ".!?";
	private final File messageFile;
	private final Random random;
	private final RiMarkov markov;
	private final long latestVoiceChannelId;

	/**
	 * Constructor.
	 *
	 * @param client
	 *      the {@link IDiscordClient}
	 * @param markov
	 *      the {@link RiMarkov}
	 */
	@Inject
	public EventListener(IDiscordClient client, RiMarkov markov,
		@Named("latestVoiceChannelId") long latestVoiceChannelId)
	{
		super(client);
		this.latestVoiceChannelId = latestVoiceChannelId;
		this.random = new Random();
		this.messageFile = new File("messages.txt");
		this.markov = markov;
	}

	/**
	 * Event received when the bot has succesfully logged in and ready.
	 *
	 * @param event
	 *      the {@link ReadyEvent}
	 */
	@EventSubscriber
	public void onReady(ReadyEvent event)
	{
		logger.info("Logged in successfully.!");
		if (latestVoiceChannelId > 0)
		{
			IVoiceChannel voiceChannel = client.getVoiceChannelByID(latestVoiceChannelId);
			logger.info("Rejoining channel " + voiceChannel.getName());
			if (voiceChannel != null)
			{
				voiceChannel.join();
			}
		}
	}

	/**
	 * Event which is received when a message is sent in a guild
	 * this bot is connected to.
	 *
	 * @param event
	 *      the {@link MessageReceivedEvent
	 */
	@EventSubscriber
	public void onMessageReceivedEvent(MessageReceivedEvent event)
	{
		IMessage message = event.getMessage();
		String content = message.getContent();
		IChannel textChannel = message.getChannel();
		Command command = Command.parseCommand(content);
		if(command != null)
		{

			String argument = command.getArgument();
			AudioPlayer player = AudioPlayer.getAudioPlayerForGuild(message.getGuild());
			EventDispatcher dispatcher = client.getDispatcher();
			KensaEvent kensaEvent = null;
			Action action = command.getAction();

			if (!action.hasPermission(message.getAuthor(), message.getGuild())){
				sendMessage(textChannel, "You don't have permission do to that, you filthy fool!");
				return;
			}

			switch(action)
			{
				/* Text channel commands */
				case HELP:
					kensaEvent = new HelpEvent(textChannel);
					break;
				case BABYLON:
					kensaEvent = new BabylonEvent(textChannel);
					break;
				case INSULT:
					String[] insultArgs = argument.split(" ");
					String insultType = insultArgs[0];
					if(insultType.startsWith("<"))
					{
						String userId = insultType.replaceAll("[^\\d]", "");
						IUser userToInsult = message.getGuild().getUserByID(Long.parseLong(userId));
						if(userToInsult != null)
						{
							kensaEvent = new InsultEvent(textChannel, userToInsult);
						}
					}
					else if(insultType.equals("add"))
					{
						String insult = StringUtils
							.join(Arrays.copyOfRange(insultArgs, 1, insultArgs.length), " ");
						kensaEvent = new InsultPersistEvent(textChannel, true, insult);
					}
					else if(insultType.equals("remove"))
					{
						kensaEvent = new InsultPersistEvent(textChannel, false, null);
					}
					break;
				/* Voice channel commands */
				case JOIN:
					kensaEvent = new JoinVoiceChannelEvent(textChannel, argument);
					break;
				case LEAVE:
					kensaEvent = new LeaveVoiceChannelEvent(textChannel);
					break;
				/* Radio commands */
				case PLAY:
					String playArg = argument.replace("-p ", "");
					kensaEvent = new PlayAudioEvent(textChannel, player, playArg, !playArg.equals(argument));
					break;
				case SKIP:
					kensaEvent = new SkipTrackEvent(textChannel, player, argument);
					break;
				case SONG:
					kensaEvent = new CurrentTrackRequestEvent(textChannel, player);
					break;
				case LOOP:
					kensaEvent = new LoopPlaylistEvent(textChannel, player, argument);
					break;
				case SHUFFLE:
					kensaEvent = new ShufflePlaylistEvent(textChannel, player);
					break;
				case PLAYLIST:
					kensaEvent = new ShowPlaylistEvent(textChannel, player);
					break;
				case PAUSE:
					kensaEvent = new PauseEvent(textChannel, player, argument);
					break;
				case SEARCH:
					String searchArg = argument.replace("-p ", "");
					kensaEvent = new SearchYoutubeEvent(textChannel, searchArg, !searchArg.equals(argument));
					break;
				case CLEAR:
					kensaEvent = new ClearPlaylistEvent(textChannel, player);
					break;
				case RESTART:
					kensaEvent = new RestartKensaEvent(textChannel);
					break;
			}

			if(kensaEvent != null)
			{
				dispatcher.dispatch(kensaEvent);
			}
		}
		else
		{
			logMessage(content);
			if((random.nextFloat() * 100) > 99)
			{
				sendMessage(textChannel, "YEAH, " + message.getContent().toUpperCase());
			}
		}
	}

	/**
	 * Logs the message to the message file.
	 * Will also update {@link RiMarkov} with the
	 * message.
	 *
	 * @param message
	 *      the message
	 */
	private void logMessage(String message)
	{
		StringBuilder sb = new StringBuilder();
		for(String word : message.split(" "))
		{
			if(UrlValidator.getInstance().isValid(word) == false
					&& word.matches("<@!*\\d+>") == false)
			{
				sb.append(" ");
				sb.append(word);
			}
		}
		String urlFreeMessage = sb.toString();
		urlFreeMessage = urlFreeMessage.trim();
		if(urlFreeMessage.isEmpty() == false)
		{
			urlFreeMessage = formatSentence(urlFreeMessage);
			markov.loadText(urlFreeMessage);
			try(FileWriter writer = new FileWriter(messageFile, true))
			{
				writer.write(urlFreeMessage);
			}
			catch(IOException e)
			{
				logger.error("Error writing message to messages file.", e);
			}
		}
	}

	/**
	 * Adds white spaces after dots and makes the character
	 * after the dot and whitespace upper case.
	 *
	 * @param message
	 *      the message to format
	 *
	 * @return
	 *      the formatted sentence
	 */
	private static String formatSentence(String message)
	{
		// Make the first character upper case and append a dot
		// to the end of the string if there wasn't any.
		message = Character.toUpperCase(message.charAt(0)) + message.substring(1);
		if(message.charAt(message.length()-1) != '.')
		{
			message += ".";
		}

		StringBuilder sb = new StringBuilder();
		char[] chars = message.toCharArray();
		for(int i=0; i<chars.length; i++)
		{
			char c = chars[i];
			sb.append(c);
			if(PUNCTUATIONS.contains("" + c) && i <= chars.length-2)
			{
				char c2 = chars[++i];
				char c3;
				if(c2 != ' ')
				{
					sb.append(' ');
					c3 = c2;
				}
				else
				{
					c3 = chars[++i];
				}
				sb.append(Character.toUpperCase(c3));
			}
		}
		return sb.toString();
	}
}