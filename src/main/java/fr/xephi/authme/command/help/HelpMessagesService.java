package fr.xephi.authme.command.help;

import fr.xephi.authme.command.CommandDescription;
import fr.xephi.authme.command.CommandUtils;
import fr.xephi.authme.initialization.DataFolder;
import fr.xephi.authme.util.FileUtils;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;

/**
 * Manages translatable help messages.
 */
public class HelpMessagesService {

    // FIXME: Make configurable and reloadable
    private String file = "/messages/help_en.yml";
    private FileConfiguration fileConfiguration;

    public HelpMessagesService(@DataFolder File dataFolder) {
        File messagesFile = new File(dataFolder, "help_en.yml");
        if (!FileUtils.copyFileFromResource(messagesFile, file)) {
            throw new IllegalStateException("Could not copy help message");
        }
        fileConfiguration = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public String getMessage(HelpMessageKey key) {
        String message = fileConfiguration.getString(key.getKey());
        return message == null
            ? key.getFallback()
            : message;
    }

    public String getDescription(CommandDescription command) {
        String key = "command." + getCommandPath(command);
        String message = fileConfiguration.getString(key);
        return message == null
            ? command.getDescription()
            : message;
    }

    private static String getCommandPath(CommandDescription command) {
        List<CommandDescription> pathElements = CommandUtils.constructParentList(command);
        String result = "";
        for (int i = 0; i < pathElements.size(); ++i) {
            result += (i == 0 ? "" : ".") + pathElements.get(i).getLabels().get(0);
        }
        return result;
    }

}
