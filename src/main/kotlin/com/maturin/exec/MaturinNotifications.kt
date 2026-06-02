package com.maturin.exec

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/** Thin wrapper around the "Maturin" notification group declared in plugin.xml. */
object MaturinNotifications {

    private fun group() =
        NotificationGroupManager.getInstance().getNotificationGroup("Maturin")

    fun info(project: Project, title: String, content: String) =
        group().createNotification(title, content, NotificationType.INFORMATION).notify(project)

    fun warn(project: Project, title: String, content: String) =
        group().createNotification(title, content, NotificationType.WARNING).notify(project)

    fun error(project: Project, title: String, content: String) =
        group().createNotification(title, content, NotificationType.ERROR).notify(project)
}
