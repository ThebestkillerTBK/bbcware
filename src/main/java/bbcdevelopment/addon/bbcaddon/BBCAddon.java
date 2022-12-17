package bbcdevelopment.addon.bbcaddon;

import bbcdevelopment.addon.bbcaddon.utils.security.Initialization;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BBCAddon extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger(BBCAddon.class);

    public static final String VERSION = "v.1.0.4";

    public static final Category BBC = new Category("BBC", Items.RED_BED.getDefaultStack());

    @Override
    public void onInitialize() {
        Initialization.init();
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(BBC);
    }

    @Override
    public String getPackage() {
        return "bbcdevelopment.addon.bbcaddon";
    }
}
