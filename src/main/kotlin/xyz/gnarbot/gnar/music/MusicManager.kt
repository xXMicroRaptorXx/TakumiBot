package xyz.gnarbot.gnar.music

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.beam.BeamAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.beam.BeamAudioTrack
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioTrack
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.VoiceChannel
import xyz.gnarbot.gnar.guilds.GuildData
import xyz.gnarbot.gnar.utils.Context
import xyz.gnarbot.gnar.utils.YouTube

class MusicManager(private val guildData: GuildData) {
    companion object {
        private val playerManager: AudioPlayerManager = DefaultAudioPlayerManager().apply {
            registerSourceManager(YoutubeAudioSourceManager())
            registerSourceManager(SoundCloudAudioSourceManager())
            registerSourceManager(VimeoAudioSourceManager())
            registerSourceManager(BandcampAudioSourceManager())
            registerSourceManager(TwitchStreamAudioSourceManager())
            registerSourceManager(BeamAudioSourceManager())
        }
    }

    var isSetup = false

    /** @return Audio player for the guild. */
    lateinit var player: AudioPlayer

    /**  @return Track scheduler for the player.*/
    lateinit var scheduler: TrackScheduler

    /** @return Wrapper around AudioPlayer to use it as an AudioSendHandler. */
    lateinit var sendHandler: AudioPlayerSendHandler

    /**
     * @return Voting cooldown.
     */
    var lastVoteTime: Long = 0L

    /**
     * @return Whether there is a vote to skip the song or not.
     */
    var isVotingToSkip = false

    var youtubeResultsMap = mutableMapOf<Member, Pair<List<YouTube.Result>, Long>>()

    fun setup() {
        player = playerManager.createPlayer()
        scheduler = TrackScheduler(guildData, player)
        sendHandler = AudioPlayerSendHandler(player)

        player.addListener(scheduler)
        player.volume = 35

        isSetup = true
    }

    fun reset() {
        //scheduler.queue.clear()
        player.destroy()
        closeAudioConnection()

        isSetup = false
    }

    fun openAudioConnection(channel: VoiceChannel, context: Context) : Boolean {
        when {
            !context.bot.config.musicEnabled -> {
                context.send().error("Music is disabled.").queue()
                return false;
            }
            !guildData.guild.selfMember.hasPermission(channel, Permission.VOICE_CONNECT) -> {
                context.send().error("The bot can't connect to this channel due to a lack of permission.").queue()
                return false
            }
            else -> {
                guildData.guild.audioManager.sendingHandler = sendHandler
                guildData.guild.audioManager.openAudioConnection(channel)

                context.send().embed("Music Playback") {
                    color = context.bot.config.musicColor
                    description = "Joining channel `${channel.name}`."
                }.action().queue()
                return true
            }
        }
    }

    fun closeAudioConnection() {
        guildData.guild.audioManager.closeAudioConnection()
        guildData.guild.audioManager.sendingHandler = null
    }

    fun loadAndPlay(context: Context, trackUrl: String) {
        playerManager.loadItemOrdered(this, trackUrl, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) {
                if (scheduler.queue.size >= context.bot.config.queueLimit) {
                    context.send().error("The queue can not exceed ${context.bot.config.queueLimit} songs.").queue()
                    return
                }

                if (track !is TwitchStreamAudioTrack && track !is BeamAudioTrack) {
                    if (track.duration > context.bot.config.durationLimit.toMillis()) {
                        context.send().error("The track can not exceed ${context.bot.config.durationLimitText}.").queue()
                        return
                    }
                }

                track.userData = context.member

                scheduler.queue(track)

                context.send().embed("Music Queue") {
                    color = context.bot.config.musicColor
                    description = "Added __**[${track.info.title}](${track.info.uri})**__ to queue."
                }.action().queue()
            }

            override fun playlistLoaded(playlist: AudioPlaylist) {
                val tracks = playlist.tracks

                var added = 0
                for (track in tracks) {
                    if (scheduler.queue.size >= context.bot.config.queueLimit) {
                        context.send().info("Ignored ${tracks.size - added} songs as the queue can not exceed ${context.bot.config.queueLimit} songs.").queue()
                        break
                    }

                    track.userData = context.member

                    scheduler.queue(track)
                    added++
                }

                context.send().embed("Music Queue") {
                    color = context.bot.config.musicColor
                    description = "Added `$added` tracks to queue from playlist `${playlist.name}`."
                }.action().queue()
            }

            override fun noMatches() {
                context.send().error("Nothing found by `$trackUrl`.").queue()
            }

            override fun loadFailed(e: FriendlyException) {
                context.send().exception(e).queue()
            }
        })
    }
}
