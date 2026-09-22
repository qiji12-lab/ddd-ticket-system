package com.ticketide.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑：
 * <pre>
 *  direct exchange   —— 按路由键投递（创建/更新/完成/指派，供不同消费者各取所需）
 *  fanout exchange   —— 广播通知（统一投递到通知队列，由 EmailConsumer 消费发邮件）
 *  dlx exchange+队列 —— 死信交换机/队列：消费失败（nack 且不重回队列）的消息进入这里，避免静默丢失
 * </pre>
 * 说明：fanout 只绑定"有消费者"的队列。曾经还存在一个 ticket.email.queue 但没有消费者，
 * 消息只进不出会无限堆积，属于典型的 MQ 拓扑隐患，已移除。
 */
@Configuration
@ConditionalOnProperty(name = "spring.rabbitmq.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMQConfig {

    public static final String TICKET_EXCHANGE = "ticket.direct.exchange";
    public static final String TICKET_FANOUT_EXCHANGE = "ticket.fanout.exchange";

    /**
     * 死信交换机与死信队列：邮件发送反复失败的消息落到这里，人工排查后可从死信队列重新投递
     */
    public static final String TICKET_DLX_EXCHANGE = "ticket.dlx.exchange";
    public static final String TICKET_DLQ_QUEUE = "ticket.dead.queue";
    public static final String TICKET_DLQ_ROUTING_KEY = "ticket.dead";

    public static final String TICKET_CREATE_QUEUE = "ticket.create.queue";
    public static final String TICKET_UPDATE_QUEUE = "ticket.update.queue";
    public static final String TICKET_COMPLETE_QUEUE = "ticket.complete.queue";
    public static final String TICKET_ASSIGN_QUEUE = "ticket.assign.queue";
    public static final String TICKET_NOTIFICATION_QUEUE = "ticket.notification.queue";

    public static final String TICKET_CREATE_ROUTING_KEY = "ticket.create";
    public static final String TICKET_UPDATE_ROUTING_KEY = "ticket.update";
    public static final String TICKET_COMPLETE_ROUTING_KEY = "ticket.complete";
    public static final String TICKET_ASSIGN_ROUTING_KEY = "ticket.assign";

    /**
     * JSON 消息转换器：生产端直接发对象、消费端直接收对象，
     * 避免"手工序列化成字符串、消费端再手工反序列化"这种容易两边不一致的写法
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public DirectExchange ticketExchange() {
        return new DirectExchange(TICKET_EXCHANGE, true, false);
    }

    @Bean
    public FanoutExchange ticketFanoutExchange() {
        return new FanoutExchange(TICKET_FANOUT_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(TICKET_DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(TICKET_DLQ_QUEUE).build();
    }

    @Bean
    public Binding deadLetterBinding(DirectExchange deadLetterExchange, Queue deadLetterQueue) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(TICKET_DLQ_ROUTING_KEY);
    }

    @Bean
    public Queue createQueue() {
        return new Queue(TICKET_CREATE_QUEUE, true);
    }

    @Bean
    public Queue updateQueue() {
        return new Queue(TICKET_UPDATE_QUEUE, true);
    }

    @Bean
    public Queue completeQueue() {
        return new Queue(TICKET_COMPLETE_QUEUE, true);
    }

    @Bean
    public Queue assignQueue() {
        return new Queue(TICKET_ASSIGN_QUEUE, true);
    }

    /**
     * 通知队列绑定死信交换机：消费失败（nack 且 requeue=false）的消息会进入死信队列
     */
    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(TICKET_NOTIFICATION_QUEUE)
                .deadLetterExchange(TICKET_DLX_EXCHANGE)
                .deadLetterRoutingKey(TICKET_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding createBinding(@Qualifier("ticketExchange") DirectExchange exchange, Queue createQueue) {
        return BindingBuilder.bind(createQueue).to(exchange).with(TICKET_CREATE_ROUTING_KEY);
    }

    @Bean
    public Binding updateBinding(@Qualifier("ticketExchange") DirectExchange exchange, Queue updateQueue) {
        return BindingBuilder.bind(updateQueue).to(exchange).with(TICKET_UPDATE_ROUTING_KEY);
    }

    @Bean
    public Binding completeBinding(@Qualifier("ticketExchange") DirectExchange exchange, Queue completeQueue) {
        return BindingBuilder.bind(completeQueue).to(exchange).with(TICKET_COMPLETE_ROUTING_KEY);
    }

    @Bean
    public Binding assignBinding(@Qualifier("ticketExchange") DirectExchange exchange, Queue assignQueue) {
        return BindingBuilder.bind(assignQueue).to(exchange).with(TICKET_ASSIGN_ROUTING_KEY);
    }

    @Bean
    public Binding notificationBinding(FanoutExchange exchange, Queue notificationQueue) {
        return BindingBuilder.bind(notificationQueue).to(exchange);
    }
}
