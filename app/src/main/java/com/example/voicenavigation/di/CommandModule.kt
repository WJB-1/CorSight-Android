package com.example.voicenavigation.di

import com.example.voicenavigation.command.MenuCommand
import com.example.voicenavigation.command.commands.*
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
@InstallIn(SingletonComponent::class)
abstract class CommandModule {

    @Binds @IntoMap @StringKey("navigate_to")
    abstract fun bindNavigateTo(cmd: NavigateToCommand): MenuCommand

    @Binds @IntoMap @StringKey("stop_navigation")
    abstract fun bindStopNavigation(cmd: StopNavigationCommand): MenuCommand

    @Binds @IntoMap @StringKey("start_obstacle_avoidance")
    abstract fun bindStartObstacle(cmd: StartObstacleCommand): MenuCommand

    @Binds @IntoMap @StringKey("stop_obstacle_avoidance")
    abstract fun bindStopObstacle(cmd: StopObstacleCommand): MenuCommand

    @Binds @IntoMap @StringKey("voice_assistant")
    abstract fun bindVoiceAssistant(cmd: VoiceAssistantCommand): MenuCommand

    @Binds @IntoMap @StringKey("preview_route")
    abstract fun bindPreviewRoute(cmd: PreviewRouteCommand): MenuCommand

    @Binds @IntoMap @StringKey("where_am_i")
    abstract fun bindWhereAmI(cmd: WhereAmICommand): MenuCommand

    @Binds @IntoMap @StringKey("repeat_last")
    abstract fun bindRepeatLast(cmd: RepeatLastCommand): MenuCommand

    @Binds @IntoMap @StringKey("query_status")
    abstract fun bindQueryStatus(cmd: QueryStatusCommand): MenuCommand

    @Binds @IntoMap @StringKey("text_search")
    abstract fun bindTextSearch(cmd: TextSearchCommand): MenuCommand

    @Binds @IntoMap @StringKey("show_history")
    abstract fun bindShowHistory(cmd: ShowHistoryCommand): MenuCommand

    @Binds @IntoMap @StringKey("show_settings")
    abstract fun bindShowSettings(cmd: ShowSettingsCommand): MenuCommand

    @Binds @IntoMap @StringKey("data_collection")
    abstract fun bindDataCollection(cmd: DataCollectionCommand): MenuCommand
}
