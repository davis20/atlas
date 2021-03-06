package org.openstreetmap.atlas.utilities.configuration;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.openstreetmap.atlas.exception.CoreException;
import org.openstreetmap.atlas.streaming.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.base.Joiner;

/**
 * Standard implementation of the Configuration interface supporting dot-notation key-value lookup.
 *
 * @author cstaylor
 * @author brian_l_davis
 */
public class StandardConfiguration implements Configuration
{
    /**
     * Configurable implementation that pulls from the outer class's data table
     *
     * @param <Raw>
     *            configured type
     * @param <Transformed>
     *            transformed type
     * @author cstaylor
     * @author brian_l_davis
     */
    private final class StandardConfigurable<Raw, Transformed> implements Configurable
    {
        private final String key;
        private final Transformed defaultValue;
        private final Function<Raw, Transformed> transform;

        private StandardConfigurable(final String key, final Raw defaultValue,
                final Function<Raw, Transformed> transform)
        {
            this.key = key;
            this.transform = transform;
            this.defaultValue = Optional.ofNullable(defaultValue).map(transform).orElse(null);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <Type> Type value()
        {
            try
            {
                final Raw found = (Raw) resolve(this.key,
                        StandardConfiguration.this.configurationData);
                return (Type) Optional.ofNullable(found).map(this.transform)
                        .orElse(this.defaultValue);
            }
            catch (final ClassCastException e)
            {
                logger.error(String.format("Invalid configuration type for %s", this.key), e);
            }
            return null;
        }

        @Override
        public <Type> Optional<Type> valueOption()
        {
            return Optional.ofNullable(value());
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(StandardConfiguration.class);
    private Map<String, Object> configurationData;
    private final String name;

    @SuppressWarnings("unchecked")
    public StandardConfiguration(final Resource resource)
    {
        this.name = resource.getName();
        try
        {
            final ObjectMapper objectMapper = new ObjectMapper();
            final SimpleModule simpleModule = new SimpleModule();
            simpleModule.addDeserializer(Map.class, new ConfigurationDeserializer());
            objectMapper.registerModule(simpleModule);
            final JsonParser parser = new JsonFactory().createParser(resource.read());

            this.configurationData = objectMapper.readValue(parser, Map.class);
        }
        catch (final Exception oops)
        {
            throw new CoreException("Failure to load configuration", oops);
        }
    }

    @Override
    public Configurable get(final String key)
    {
        return new StandardConfigurable<>(key, null, Function.identity());
    }

    @Override
    public <Raw, Transformed> Configurable get(final String key,
            final Function<Raw, Transformed> transform)
    {
        return new StandardConfigurable<>(key, null, transform);
    }

    @Override
    public Configurable get(final String key, final Object defaultValue)
    {
        return new StandardConfigurable<>(key, defaultValue, Function.identity());
    }

    @Override
    public <Raw, Transformed> Configurable get(final String key, final Raw defaultValue,
            final Function<Raw, Transformed> transform)
    {
        return new StandardConfigurable<>(key, defaultValue, transform);
    }

    @Override
    public String toString()
    {
        return this.name != null ? this.name : super.toString();
    }

    @SuppressWarnings("unchecked")
    private Object resolve(final String key, final Map<String, Object> currentContext)
    {
        if (StringUtils.isEmpty(key))
        {
            return currentContext;
        }
        final LinkedList<String> rootParts = new LinkedList<>(Arrays.asList(key.split("\\.")));
        final LinkedList<String> childParts = new LinkedList<>();
        while (!rootParts.isEmpty())
        {
            final String currentKey = Joiner.on(".").join(rootParts);
            final Object nextItem = currentContext.get(currentKey);
            if (nextItem instanceof Map)
            {
                final String nextKey = Joiner.on(".").join(childParts);
                return resolve(nextKey, (Map<String, Object>) nextItem);
            }
            if (nextItem != null)
            {
                return nextItem;
            }
            childParts.addFirst(rootParts.removeLast());
        }
        return null;
    }
}
